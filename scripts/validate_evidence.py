#!/usr/bin/env python3
"""
Validate evidence completeness from all CI jobs.
"""

import argparse
import json
import sys
from pathlib import Path


def check_file_exists(path: Path, required: bool = True) -> bool:
    """Check if a file exists."""
    if path.exists():
        print(f"✅ Found: {path}")
        return True
    else:
        msg = f"{'❌' if required else '⚠️'} Missing: {path}"
        print(msg)
        return not required


def validate_allocation_evidence(path: Path) -> dict:
    """Validate RT allocation guard evidence."""
    print("\n[RT Allocation Guard Evidence]")
    
    errors = []
    
    allocation_report = path / "allocation-report.json"
    vad_allocations = path / "vad-allocations.json"
    
    if not check_file_exists(allocation_report):
        errors.append("Missing allocation-report.json")
    else:
        with open(allocation_report) as f:
            data = json.load(f)
            if data.get("total_allocations", -1) > 0:
                errors.append(f"RT path allocations detected: {data['total_allocations']}")
    
    if not check_file_exists(vad_allocations):
        errors.append("Missing vad-allocations.json")
    else:
        with open(vad_allocations) as f:
            data = json.load(f)
            if data.get("vad_allocations", -1) > 0:
                errors.append(f"VAD allocations detected: {data['vad_allocations']}")
    
    return {"passed": len(errors) == 0, "errors": errors}


def validate_transport_evidence(path: Path) -> dict:
    """Validate transport validation evidence."""
    print("\n[Transport Validation Evidence]")
    
    errors = []
    
    simulated = path / "simulated-validation.json"
    kotlin = path / "kotlin-transport-validation.json"
    cpp = path / "cpp-transport-validation.json"
    
    if not check_file_exists(simulated):
        errors.append("Missing simulated-validation.json")
    
    if not check_file_exists(kotlin):
        errors.append("Missing kotlin-transport-validation.json")
    
    if not check_file_exists(cpp):
        errors.append("Missing cpp-transport-validation.json")
    
    # Check for Gradle reports
    gradle_reports = path / "reports"
    if gradle_reports.exists():
        print(f"✅ Found Gradle reports: {gradle_reports}")
    else:
        print(f"⚠️ Missing Gradle reports: {gradle_reports}")
    
    return {"passed": len(errors) == 0, "errors": errors}


def validate_gateway_evidence(path: Path) -> dict:
    """Validate gateway integration test evidence."""
    print("\n[Gateway Integration Test Evidence]")
    
    errors = []
    
    coverage = path / "coverage.xml"
    htmlcov = path / "htmlcov"
    
    if not check_file_exists(coverage):
        errors.append("Missing coverage.xml")
    
    if not check_file_exists(htmlcov, required=False):
        pass
    
    return {"passed": len(errors) == 0, "errors": errors}


def validate_android_evidence(path: Path) -> dict:
    """Validate Android build and test evidence."""
    print("\n[Android Build & Test Evidence]")
    
    errors = []
    
    test_results = path / "test-results"
    reports = path / "reports"
    
    if not check_file_exists(test_results):
        errors.append("Missing test-results")
    
    if not check_file_exists(reports):
        errors.append("Missing reports")
    
    return {"passed": len(errors) == 0, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description="Validate evidence completeness")
    parser.add_argument("--allocation", required=True, help="Allocation guard artifacts path")
    parser.add_argument("--transport", required=True, help="Transport validation artifacts path")
    parser.add_argument("--gateway", required=True, help="Gateway test artifacts path")
    parser.add_argument("--android", required=True, help="Android test artifacts path")
    
    args = parser.parse_args()
    
    allocation_path = Path(args.allocation)
    transport_path = Path(args.transport)
    gateway_path = Path(args.gateway)
    android_path = Path(args.android)
    
    allocation_result = validate_allocation_evidence(allocation_path)
    transport_result = validate_transport_evidence(transport_path)
    gateway_result = validate_gateway_evidence(gateway_path)
    android_result = validate_android_evidence(android_path)
    
    all_passed = (
        allocation_result["passed"] and
        transport_result["passed"] and
        gateway_result["passed"] and
        android_result["passed"]
    )
    
    if all_passed:
        print("\n✅ All evidence validated successfully")
    else:
        print("\n❌ Evidence validation failed")
        all_errors = []
        for name, result in [
            ("Allocation", allocation_result),
            ("Transport", transport_result),
            ("Gateway", gateway_result),
            ("Android", android_result),
        ]:
            if not result["passed"]:
                all_errors.extend([f"[{name}] {e}" for e in result["errors"]])
        
        for error in all_errors:
            print(f"  - {error}")
        sys.exit(1)


if __name__ == "__main__":
    main()
