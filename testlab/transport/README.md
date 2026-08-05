# Direct-versus-relay transport validation

`compare.py` consumes one normalized JSONL row per completed call and emits a
`triplex.transport-validation.v1` artifact. It reports nearest-rank p50/p95/p99
caller-probe latency, callback latency, jitter, sequence gaps, reordering,
duplication, and transport loss for direct PJSIP and relay WebSocket routes.

## CI-reproducible validation

From the repository root:

```bash
python3 -m unittest -v testlab.transport.test_compare

cd apps/android
./gradlew :telephony-plivo:transportValidation
cd ../..

python3 testlab/transport/compare.py simulate \
  --calls-per-route 20 \
  --seed 7 \
  --output testlab/transport/build/simulated-20-per-route.jsonl

python3 testlab/transport/compare.py compare \
  testlab/transport/build/simulated-20-per-route.jsonl \
  --validation-log apps/android/telephony-plivo/build/reports/transport/kotlin-transport-validation.json \
  --validation-log apps/android/telephony-plivo/build/reports/transport/cpp-transport-validation.json \
  --output testlab/transport/build/simulated-validation.json
```

The generated 40-call matrix is deterministic `SIMULATED` evidence. It tests
the comparator and produces useful performance statistics, but its decision is
always `NON_PHYSICAL_EVIDENCE_ONLY_NO_ROUTE_SELECTION`. Kotlin and host C++
artifacts are likewise labeled `CI_MOCK` and set `production_ready` to `false`.

## Physical call input

New logs use `triplex.transport-call.v2`. The exact required fields and type
checks live in `CallEvidence.parse`. In addition to the v1 timing/RTP/PCM
fields, v2 requires:

- `evidence_kind`: `PHYSICAL_PSTN` or `SIMULATED`;
- `test_scenario`;
- negotiated `tls_protocol`, `tls_cipher`, `tls_verify_status`, and
  `srtp_active`;
- `network_handoff_attempted` / `network_handoff_recovered`;
- `abrupt_socket_drop_observed` / `cleanup_complete`.

Legacy v1 rows can still be summarized, but are classified
`UNCLASSIFIED_LEGACY` and cannot satisfy a physical-evidence gate. RTP/provider
timestamps establish sequence and jitter behavior; they are not treated as a
synchronized one-way latency clock. `caller_heard_probe_ms` must come from the
caller-side probe.

At least 20 unique calls are required for each route. The direct set must
include a recovered validated-network handoff and completed abrupt-drop
cleanup. Both structured Android validation suites must also pass. Run the
physical comparison with the same `compare` command and optionally add
`--require-review-eligible` for a nonzero CI exit when any bounded gate fails.

Even a fully passing artifact only says `ELIGIBLE_FOR_HUMAN_REVIEW` and keeps
`production_ready: false`. The physical device/geography matrix, p99/soak,
security, outage, and rollback gates in `UNIFICATION_PLAN.md` remain required.
