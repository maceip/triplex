import json
import tempfile
import unittest
from pathlib import Path

from testlab.transport.compare import (
    ARTIFACT_SCHEMA,
    CALL_SCHEMA,
    LEGACY_CALL_SCHEMA,
    REQUIRED_VALIDATION_CHECKS,
    VALIDATION_SUITE_SCHEMA,
    compare,
    load,
    load_validation_suites,
    main,
    percentile,
    simulate_calls,
)


def row(
    route: str,
    run: int,
    heard_ms: float,
    *,
    evidence_kind: str = "PHYSICAL_PSTN",
) -> dict[str, object]:
    direct = route == "DIRECT_PJSIP"
    handoff = direct and run == 0
    abrupt_drop = direct and run == 1
    return {
        "schema": CALL_SCHEMA,
        "evidence_kind": evidence_kind,
        "run_id": f"{route.lower()}-{run}",
        "route": route,
        "test_scenario": (
            "WIFI_TO_CELLULAR_HANDOFF"
            if handoff
            else "ABRUPT_SOCKET_DROP"
            if abrupt_drop
            else "BASELINE"
        ),
        "device_model": "physical-test-device",
        "network": "WIFI_TO_LTE" if handoff else "WIFI",
        "secure_signaling": True,
        "encrypted_media": True,
        "tls_protocol": 16 if run % 2 == 0 else 32,
        "tls_cipher": 0xC02F if run % 2 == 0 else 0x1301,
        "tls_verify_status": 0,
        "srtp_active": direct,
        "captured_pcm_frames": 100,
        "captured_non_silent_pcm_frames": 50,
        "injected_pcm_frames": 50,
        "rx_packets": 100,
        "tx_packets": 100,
        "rx_sequence_gap_packets": 0,
        "rx_reordered_packets": 0,
        "rx_duplicate_packets": 0,
        "provider_timestamp": 16_000,
        "provider_timestamp_clock_hz": 8_000,
        "transport_jitter_us": 1_000.0,
        "transport_lost_packets": 0,
        "source_media_address": "203.0.113.10:40000",
        "capture_dropped_frames": 0,
        "synth_dropped_frames": 0,
        "callback_to_enqueue_p95_ms": 1.0,
        "caller_heard_probe_ms": heard_ms,
        "interrupt_to_caller_silence_ms": 90.0,
        "network_handoff_attempted": handoff,
        "network_handoff_recovered": handoff,
        "abrupt_socket_drop_observed": abrupt_drop,
        "cleanup_complete": abrupt_drop,
    }


def suite(suite_name: str, *, failed: str | None = None) -> dict[str, object]:
    return {
        "schema": VALIDATION_SUITE_SCHEMA,
        "suite": suite_name,
        "evidence_kind": "CI_MOCK",
        "checks": [
            {
                "id": check_id,
                "status": "FAIL" if check_id == failed else "PASS",
            }
            for check_id in sorted(REQUIRED_VALIDATION_CHECKS[suite_name])
        ],
        "all_passed": failed is None,
        "production_ready": False,
    }


def write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.write_text("".join(json.dumps(value) + "\n" for value in values))


def write_suites(directory: Path) -> list[Path]:
    paths = []
    for suite_name in REQUIRED_VALIDATION_CHECKS:
        path = directory / f"{suite_name}.json"
        path.write_text(json.dumps(suite(suite_name)))
        paths.append(path)
    return paths


class CompareTest(unittest.TestCase):
    def test_nearest_rank_percentiles_are_reproducible_for_twenty_calls(self) -> None:
        values = [float(value) for value in range(1, 21)]
        self.assertEqual(10.0, percentile(values, 0.50))
        self.assertEqual(19.0, percentile(values, 0.95))

    def test_selects_direct_for_review_only_with_physical_and_ci_evidence(self) -> None:
        values = [row("DIRECT_PJSIP", index, 80.0) for index in range(20)]
        values += [row("RELAY_WEBSOCKET", index, 120.0) for index in range(20)]
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            calls = directory / "physical.jsonl"
            write_jsonl(calls, values)
            validations = load_validation_suites(write_suites(directory))
            report = compare(load([calls]), validations)

        self.assertTrue(report["evidence"]["sample_count_sufficient"])
        self.assertTrue(report["evidence"]["all_calls_physical_pstn"])
        self.assertEqual(
            "DIRECT_PJSIP_PREFERRED_FOR_REVIEW",
            report["benchmark"]["decision"],
        )
        self.assertEqual(
            "ELIGIBLE_FOR_HUMAN_REVIEW",
            report["production_readiness"]["status"],
        )
        self.assertFalse(report["production_readiness"]["production_ready"])

    def test_reports_weighted_packet_rates_and_latency_percentiles(self) -> None:
        values = [row("DIRECT_PJSIP", index, 80.0 + index) for index in range(20)]
        values += [row("RELAY_WEBSOCKET", index, 120.0) for index in range(20)]
        values[0]["rx_sequence_gap_packets"] = 10
        values[0]["rx_reordered_packets"] = 4
        values[0]["rx_duplicate_packets"] = 2
        values[0]["transport_lost_packets"] = 5
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "calls.jsonl"
            write_jsonl(path, values)
            report = compare(load([path]))

        direct = report["routes"]["DIRECT_PJSIP"]
        rates = direct["packet_quality"]["aggregate_rates"]
        self.assertAlmostEqual(10 / 2_010, rates["sequence_gap_rate"])
        self.assertAlmostEqual(4 / 2_000, rates["reordering_rate"])
        self.assertAlmostEqual(2 / 2_000, rates["duplication_rate"])
        self.assertAlmostEqual(5 / 2_005, rates["transport_loss_rate"])
        self.assertEqual(89.0, direct["latency_ms"]["caller_heard_probe"]["p50"])
        self.assertEqual(98.0, direct["latency_ms"]["caller_heard_probe"]["p95"])

    def test_simulated_twenty_call_matrix_never_selects_a_route(self) -> None:
        values = simulate_calls(calls_per_route=20, seed=19)
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "simulated.jsonl"
            write_jsonl(path, values)
            report = compare(load([path]))

        self.assertEqual(40, report["evidence"]["total_calls"])
        self.assertTrue(report["evidence"]["sample_count_sufficient"])
        self.assertFalse(report["evidence"]["all_calls_physical_pstn"])
        self.assertEqual(
            "NON_PHYSICAL_EVIDENCE_ONLY_NO_ROUTE_SELECTION",
            report["benchmark"]["decision"],
        )
        self.assertIn(
            "PHYSICAL_PSTN_EVIDENCE_REQUIRED",
            report["production_readiness"]["blockers"],
        )

    def test_simulator_is_seeded_and_covers_recovery_scenarios(self) -> None:
        first = simulate_calls(calls_per_route=20, seed=23)
        second = simulate_calls(calls_per_route=20, seed=23)
        self.assertEqual(first, second)
        direct = [value for value in first if value["route"] == "DIRECT_PJSIP"]
        self.assertTrue(any(value["network_handoff_recovered"] for value in direct))
        self.assertTrue(any(value["cleanup_complete"] for value in direct))
        self.assertEqual({16, 32}, {value["tls_protocol"] for value in direct})

    def test_never_selects_with_too_few_calls(self) -> None:
        values = [row("DIRECT_PJSIP", 1, 80.0), row("RELAY_WEBSOCKET", 1, 120.0)]
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "fixture.jsonl"
            write_jsonl(path, values)
            report = compare(load([path]))
        self.assertFalse(report["evidence"]["sample_count_sufficient"])
        self.assertEqual(
            "INSUFFICIENT_CALL_EVIDENCE", report["benchmark"]["decision"]
        )

    def test_rejects_rows_without_provider_timestamp_evidence(self) -> None:
        value = row("DIRECT_PJSIP", 1, 80.0)
        del value["provider_timestamp"]
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "invalid.jsonl"
            path.write_text(json.dumps(value) + "\n")
            with self.assertRaisesRegex(ValueError, "provider_timestamp"):
                load([path])

    def test_rejects_duplicate_route_run_ids_across_input_files(self) -> None:
        value = row("DIRECT_PJSIP", 3, 80.0)
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            first = directory / "first.jsonl"
            second = directory / "second.jsonl"
            write_jsonl(first, [value])
            write_jsonl(second, [value])
            with self.assertRaisesRegex(ValueError, "duplicate route/run_id"):
                load([first, second])

    def test_failed_required_ci_check_blocks_physical_route_selection(self) -> None:
        values = [row("DIRECT_PJSIP", index, 80.0) for index in range(20)]
        values += [row("RELAY_WEBSOCKET", index, 120.0) for index in range(20)]
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            calls = directory / "physical.jsonl"
            write_jsonl(calls, values)
            kotlin_path = directory / "kotlin.json"
            cpp_path = directory / "cpp.json"
            kotlin_path.write_text(
                json.dumps(suite("kotlin_transport_policy", failed="missing_srtp_rejected"))
            )
            cpp_path.write_text(json.dumps(suite("cpp_transport_lifecycle")))
            validations = load_validation_suites([kotlin_path, cpp_path])
            report = compare(load([calls]), validations)

        self.assertFalse(report["supporting_validation"]["complete"])
        self.assertEqual(
            "SUPPORTING_VALIDATION_INCOMPLETE",
            report["benchmark"]["decision"],
        )
        self.assertIn(
            "REQUIRED_CI_VALIDATION_MISSING_OR_FAILED",
            report["production_readiness"]["blockers"],
        )

    def test_legacy_rows_are_benchmarkable_but_never_physical_evidence(self) -> None:
        value = row("DIRECT_PJSIP", 1, 80.0)
        value["schema"] = LEGACY_CALL_SCHEMA
        for key in (
            "evidence_kind",
            "test_scenario",
            "tls_protocol",
            "tls_cipher",
            "tls_verify_status",
            "srtp_active",
            "network_handoff_attempted",
            "network_handoff_recovered",
            "abrupt_socket_drop_observed",
            "cleanup_complete",
        ):
            del value[key]
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "legacy.jsonl"
            write_jsonl(path, [value])
            parsed = load([path])[0]
        self.assertEqual("UNCLASSIFIED_LEGACY", parsed.evidence_kind)

    def test_rejects_validation_suite_that_claims_production_ready(self) -> None:
        value = suite("kotlin_transport_policy")
        value["production_ready"] = True
        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "invalid-suite.json"
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "cannot claim production"):
                load_validation_suites([path])

    def test_cli_emits_hashed_structured_artifact(self) -> None:
        values = simulate_calls(calls_per_route=20, seed=29)
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            calls = directory / "simulated.jsonl"
            output = directory / "artifact.json"
            write_jsonl(calls, values)
            status = main(["compare", str(calls), "--output", str(output)])
            artifact = json.loads(output.read_text())
        self.assertEqual(0, status)
        self.assertEqual(ARTIFACT_SCHEMA, artifact["schema"])
        self.assertRegex(artifact["payload_sha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(
            artifact["inputs"]["call_logs"][0]["sha256"], r"^[0-9a-f]{64}$"
        )
        self.assertFalse(artifact["production_readiness"]["production_ready"])


if __name__ == "__main__":
    unittest.main()
