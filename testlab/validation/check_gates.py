#!/usr/bin/env python3
"""
Check gate status from evidence manifest.
"""

import argparse
import json
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Check all gates passed")
    parser.add_argument("--manifest", required=True, help="Evidence manifest JSON")
    
    args = parser.parse_args()
    
    manifest_path = Path(args.manifest)
    
    if not manifest_path.exists():
        print(f"❌ Manifest not found: {manifest_path}")
        sys.exit(1)
    
    with open(manifest_path) as f:
        manifest = json.load(f)
    
    gates = manifest.get("gates", {})
    
    all_passed = all(gates.values()) if gates else False
    
    print("\nGate Status:")
    for gate, status in gates.items():
        icon = "✅" if status == "passed" else "❌"
        print(f"  {icon} {gate}: {status}")
    
    if all_passed:
        print("\n✅ All gates PASSED - Evidence accepted")
        sys.exit(0)
    else:
        print("\n❌ Gate check FAILED - Evidence rejected")
        sys.exit(1)


if __name__ == "__main__":
    main()
