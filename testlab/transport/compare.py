#!/usr/bin/env python3
"""
Transport validation comparison suite.
Parses PSTN evidence and production gate status.
"""

import argparse
import json
import sys
from pathlib import Path


@dataclass
class ValidationResult:
    passed: bool
    production_ready: bool
    physical_pstn_evidence: bool
    latency: dict
    packets: dict
    errors: list
    warnings: list
    duration_ms: float
    timestamp: str


def parse_pstn_validation(filepath: Path) -> dict:
    """Parse PSTN validation report."""
    if not filepath.exists():
        return {
            "passed": False,
            "production_ready": False,
            "physical_pstn_evidence": False,
            "errors": [f"PSTN validation file not found: {filepath}"],
            "latency": {"p50": 0, "p95": 0},
            "packets": {"dropped": -1}
        }
    
    with open(filepath) as f:
        data = json.load(f)
    
    return data


def validate_production_gates(
    pstn_path: Path,
    p50_limit: float = 5.0,
    p95_limit: float = 15.0,
    drop_limit: int = 0,
    production_required: bool = True
) -> bool:
    """Validate production gates against PSTN evidence."""
    
    pstn_data = parse_pstn_validation(pstn_path)
    
    errors = []
    
    # Gate: Production ready flag
    if production_required and not pstn_data.get("production_ready", False):
        errors.append("production_ready flag is False")
    
    # Gate: Physical PSTN evidence
    if production_required and not pstn_data.get("physical_pstn_evidence", False):
        errors.append("physical_pstn_evidence flag is False - PHYSICAL_PSTN_EVIDENCE_REQUIRED gate not cleared")
    
    # Gate: Latency p50
    latency = pstn_data.get("latency", {})
    p50 = latency.get("p50", float('inf'))
    if p50 > p50_limit:
        errors.append(f"p50 latency {p50:.2f}s exceeds limit {p50_limit}s")
    
    # Gate: Latency p95
    p95 = latency.get("p95", float('inf'))
    if p95 > p95_limit:
        errors.append(f"p95 latency {p95:.2f}s exceeds limit {p95_limit}s")
    
    # Gate: Packet drops
    packets = pstn_data.get("packets", {})
    dropped = packets.get("dropped", -1)
    if dropped > drop_limit:
        errors.append(f"Packet drops {dropped} exceeds limit {drop_limit}")
    
    if errors:
        print("❌ Production gate validation FAILED:")
        for error in errors:
            print(f"  - {error}")
        return False
    else:
        print("✅ Production gates PASSED")
        print(f"  Production ready: {pstn_data.get('production_ready', False)}")
        print(f"  PSTN evidence: {pstn_data.get('physical_pstn_evidence', False)}")
        print(f"  p50 latency: {p50:.2f}s")
        print(f"  p95 latency: {p95:.2f}s")
        print(f"  Packet drops: {dropped}")
        return True


def merge_validations(
    simulated_path: Path,
    kotlin_path: Path,
    cpp_path: Path,
    pstn_path: Path
) -> dict:
    """Merge all validation results."""
    
    merged = {
        "simulated": parse_pstn_validation(simulated_path) if simulated_path.exists() else None,
        "kotlin": parse_pstn_validation(kotlin_path) if kotlin_path.exists() else None,
        "cpp": parse_pstn_validation(cpp_path) if cpp_path.exists() else None,
        "pstn": parse_pstn_validation(pstn_path) if pstn_path.exists() else None,
    }
    
    # Overall passed
    all_passed = all(
        v.get("passed", False) if v else False 
        for v in merged.values()
    )
    
    merged["overall_passed"] = all_passed
    merged["production_ready"] = merged["pstn"].get("production_ready", False) if merged["pstn"] else False
    
    return merged


def main():
    parser = argparse.ArgumentParser(description="Transport validation with PSTN evidence")
    parser.add_argument("--mode", 
                       choices=["simulated", "kotlin", "cpp", "pstn", "all"],
                       default="all",
                       help="Validation mode")
    parser.add_argument("--simulated", type=Path, 
                       default=Path("artifacts/simulated-validation.json"),
                       help="Simulated validation JSON")
    parser.add_argument("--kotlin", type=Path,
                       default=Path("artifacts/kotlin-transport-validation.json"),
                       help="Kotlin validation JSON")
    parser.add_argument("--cpp", type=Path,
                       default=Path("artifacts/cpp-transport-validation.json"),
                       help="C++ validation JSON")
    parser.add_argument("--pstn", type=Path,
                       default=Path("testlab/transport/pstn-validation.json"),
                       help="PSTN evidence JSON")
    parser.add_argument("--p50-limit", type=float, default=5.0,
                       help="p50 latency limit (seconds)")
    parser.add_argument("--p95-limit", type=float, default=15.0,
                       help="p95 latency limit (seconds)")
    parser.add_argument("--drop-limit", type=int, default=0,
                       help="Packet drop limit")
    parser.add_argument("--production-gate", action="store_true",
                       help="Require production_ready flag")
    parser.add_argument("--output", type=Path,
                       help="Output merged validation JSON")
    
    args = parser.parse_args()
    
    if args.mode == "pstn":
        passed = validate_production_gates(
            args.pstn,
            p50_limit=args.p50_limit,
            p95_limit=args.p95_limit,
            drop_limit=args.drop_limit,
            production_required=args.production_gate
        )
    elif args.mode == "all":
        merged = merge_validations(
            args.simulated,
            args.kotlin,
            args.cpp,
            args.pstn
        )
        
        print("\nTransport Validation Summary:")
        print("=" * 50)
        
        for name in ["simulated", "kotlin", "cpp", "pstn"]:
            data = merged.get(name)
            if data:
                status = "✅ PASSED" if data.get("passed") else "❌ FAILED"
                prod_flag = data.get("production_ready", False)
                pstn_flag = data.get("physical_pstn_evidence", False)
                
                print(f"{name.upper():12} {status}")
                if name == "pstn":
                    print(f"{'':12} production_ready={prod_flag}")
                    print(f"{'':12} pstn_evidence={pstn_flag}")
        
        print("\n" + "=" * 50)
        print(f"Overall: {'✅ PASSED' if merged['overall_passed'] else '❌ FAILED'}")
        print(f"Production: {'✅ READY' if merged['production_ready'] else '❌ NOT READY'}")
        
        if args.output:
            with open(args.output, 'w') as f:
                json.dump(merged, f, indent=2)
            print(f"\nMerged validation: {args.output}")
        
        passed = merged["overall_passed"] and merged["production_ready"]
    else:
        # Specific mode validation
        mode_map = {
            "simulated": args.simulated,
            "kotlin": args.kotlin,
            "cpp": args.cpp,
        }
        
        file_path = mode_map[args.mode]
        passed = validate_production_gates(
            file_path,
            p50_limit=args.p50_limit,
            p95_limit=args.p95_limit,
            drop_limit=args.drop_limit,
            production_required=False
        )
    
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    from dataclasses import dataclass
    
    @dataclass
    class ValidationResult:
        passed: bool
        production_ready: bool
        physical_pstn_evidence: bool
        latency: dict
        packets: dict
        errors: list
        warnings: list
        duration_ms: float
        timestamp: str
    
    main()
