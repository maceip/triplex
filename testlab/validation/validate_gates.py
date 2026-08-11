#!/usr/bin/env python3
"""
Validate transport latency and packet drop gates.
"""

import argparse
import json
import sys
from pathlib import Path


def load_result(path: Path, mode: str) -> dict:
    """Load a single-mode or nested validation result."""
    with open(path) as handle:
        data = json.load(handle)

    if isinstance(data, dict) and "latency" in data and "packets" in data:
        return data

    if isinstance(data, dict) and mode in data:
        return data[mode]

    raise ValueError(
        f"{path} does not contain a ValidationResult or a '{mode}' entry"
    )


def validate_gates(
    simulated: Path,
    kotlin: Path,
    cpp: Path,
    p50_limit: float,
    p95_limit: float,
    drop_limit: int,
):
    """Validate all transport results against performance gates."""

    errors = []

    sim_data = load_result(simulated, "simulated")
    kotlin_data = load_result(kotlin, "kotlin")
    cpp_data = load_result(cpp, "cpp")

    for name, data in [("Simulated", sim_data), ("Kotlin", kotlin_data), ("C++", cpp_data)]:
        if not data.get("passed", False):
            detail = "; ".join(data.get("errors") or ["passed=false"])
            errors.append(f"{name} validation marked failed: {detail}")

        p50 = data["latency"]["p50"]
        if p50 > p50_limit:
            errors.append(f"{name} p50 latency {p50}ms exceeds limit {p50_limit}ms")

        p95 = data["latency"]["p95"]
        if p95 > p95_limit:
            errors.append(f"{name} p95 latency {p95}ms exceeds limit {p95_limit}ms")

        drops = data["packets"]["dropped"]
        if drops > drop_limit:
            errors.append(f"{name} packet drops {drops} exceeds limit {drop_limit}")

    if errors:
        print("❌ Gate validation FAILED:")
        for error in errors:
            print(f"  - {error}")
        sys.exit(1)

    print("✅ All gates PASSED")
    print(f"  p50 latency: ≤{p50_limit}ms")
    print(f"  p95 latency: ≤{p95_limit}ms")
    print(f"  packet drops: ≤{drop_limit}")


def main():
    parser = argparse.ArgumentParser(description="Validate transport performance gates")
    parser.add_argument("--simulated", required=True, help="Simulated validation JSON")
    parser.add_argument("--kotlin", required=True, help="Kotlin validation JSON")
    parser.add_argument("--cpp", required=True, help="C++ validation JSON")
    parser.add_argument("--p50-limit", type=float, default=120, help="p50 latency limit (ms)")
    parser.add_argument("--p95-limit", type=float, default=150, help="p95 latency limit (ms)")
    parser.add_argument("--drop-limit", type=int, default=0, help="Packet drop limit")

    args = parser.parse_args()

    validate_gates(
        simulated=Path(args.simulated),
        kotlin=Path(args.kotlin),
        cpp=Path(args.cpp),
        p50_limit=args.p50_limit,
        p95_limit=args.p95_limit,
        drop_limit=args.drop_limit,
    )


if __name__ == "__main__":
    main()
