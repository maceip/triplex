#!/usr/bin/env python3
"""
PSTN evidence documentation and archiving.
"""

import argparse
import hashlib
import json
import tarfile
import tempfile
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict


def get_git_info() -> Dict[str, str]:
    """Get current git information."""
    try:
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            text=True
        ).strip()
        
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            text=True
        ).strip()
        
        author = subprocess.check_output(
            ["git", "log", "-1", "--pretty=format:%an <%ae>"],
            text=True
        ).strip()
        
        message = subprocess.check_output(
            ["git", "log", "-1", "--pretty=format:%s"],
            text=True
        ).strip()
    except subprocess.CalledProcessError:
        branch = "unknown"
        commit = "unknown"
        author = "unknown"
        message = "unknown"
    
    return {
        "branch": branch,
        "commit": commit,
        "author": author,
        "message": message,
    }


def archive_evidence(evidence_dir: Path, output_archive: Path) -> Dict[str, str]:
    """Create archive of all PSTN evidence with checksums."""
    
    evidence_files = []
    checksums = {}
    
    # Collect all evidence files
    for file_path in evidence_dir.rglob("*"):
        if file_path.is_file():
            relative_path = file_path.relative_to(evidence_dir)
            
            # Compute SHA256
            sha256 = hashlib.sha256()
            with open(file_path, "rb") as f:
                for chunk in iter(lambda: f.read(8192), b""):
                    sha256.update(chunk)
            
            checksum = sha256.hexdigest()
            checksums[str(relative_path)] = {
                "sha256": checksum,
                "size": file_path.stat().st_size,
                "modified": datetime.fromtimestamp(file_path.stat().st_mtime).isoformat(),
            }
            
            evidence_files.append(relative_path)
    
    # Create tar.gz archive
    with tarfile.open(output_archive, "w:gz") as tar:
        for file_path in evidence_dir.rglob("*"):
            if file_path.is_file():
                tar.add(file_path, arcname=file_path.relative_to(evidence_dir))
    
    # Compute archive checksum
    sha256 = hashlib.sha256()
    with open(output_archive, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha256.update(chunk)
    
    archive_checksum = sha256.hexdigest()
    
    return {
        "archive_path": str(output_archive),
        "archive_sha256": archive_checksum,
        "archive_size": output_archive.stat().st_size,
        "file_count": len(evidence_files),
        "file_checksums": checksums,
        "git_info": get_git_info(),
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def create_documentation(evidence_dir: Path, output_dir: Path):
    """Create documentation of PSTN evidence."""
    
    output_dir.mkdir(parents=True, exist_ok=True)
    
    # Create archive
    archive_path = output_dir / f"pstn-evidence-{datetime.now().strftime('%Y%m%d-%H%M%S')}.tar.gz"
    archive_info = archive_evidence(evidence_dir, archive_path)
    
    # Load validation report
    validation_path = evidence_dir / "pstn-validation.json"
    if validation_path.exists():
        with open(validation_path) as f:
            validation = json.load(f)
    else:
        validation = {}
    
    # Create documentation
    doc = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "git": archive_info["git_info"],
        "evidence": {
            "archive": {
                "path": archive_info["archive_path"],
                "sha256": archive_info["archive_sha256"],
                "size_bytes": archive_info["archive_size"],
                "file_count": archive_info["file_count"],
            },
            "files": archive_info["file_checksums"],
        },
        "validation": validation,
        "production_ready": validation.get("production_ready", False),
        "physical_pstn_evidence": validation.get("physical_pstn_evidence", False),
    }
    
    doc_path = output_dir / "pstn-evidence-documentation.json"
    with open(doc_path, 'w') as f:
        json.dump(doc, f, indent=2)
    
    # Create markdown summary
    md_path = output_dir / "PSTN_EVIDENCE.md"
    with open(md_path, 'w') as f:
        f.write("# PSTN Evidence Documentation\n\n")
        f.write(f"**Generated:** {doc['generated']}\n\n")
        f.write(f"**Commit:** {doc['git']['commit'][:7]}\n\n")
        f.write(f"**Branch:** {doc['git']['branch']}\n\n")
        f.write("---\n\n")
        
        f.write("## Production Status\n\n")
        status = "✅ READY" if doc["production_ready"] else "❌ NOT READY"
        f.write(f"**Production Ready:** {status}\n\n")
        
        pstn_status = "✅ VERIFIED" if doc["physical_pstn_evidence"] else "❌ EVIDENCE REQUIRED"
        f.write(f"**Physical PSTN Evidence:** {pstn_status}\n\n")
        
        f.write("---\n\n")
        
        f.write("## Evidence Archive\n\n")
        f.write(f"- **Archive:** `{archive_path.name}`\n")
        f.write(f"- **SHA256:** `{archive_info['archive_sha256']}`\n")
        f.write(f"- **Size:** {archive_info['archive_size']:,} bytes\n")
        f.write(f"- **Files:** {archive_info['file_count']}\n\n")
        
        f.write("## Validation Summary\n\n")
        if validation:
            f.write(f"- **Calls Tested:** {validation.get('summary', {}).get('total_calls', 0)}\n")
            f.write(f"- **Successful:** {validation.get('summary', {}).get('successful_calls', 0)}\n")
            f.write(f"- **p50 Latency:** {validation.get('summary', {}).get('establishment_time_p50_ms', 0):.0f} ms\n")
            f.write(f"- **p95 Latency:** {validation.get('summary', {}).get('establishment_time_p95_ms', 0):.0f} ms\n")
            f.write(f"- **Packet Loss:** {validation.get('summary', {}).get('packet_loss_rate', 0)*100:.2f}%\n")
        
        f.write("\n---\n\n")
        f.write(f"*Per UNIFICATION_PLAN.md: No component is called production-ready without real PSTN, device, timing, interruption, and recovery evidence.*\n")
    
    print(f"\nPSTN Evidence Documentation:")
    print(f"  Archive: {archive_path}")
    print(f"  Documentation: {doc_path}")
    print(f"  Summary: {md_path}")
    
    return doc


def main():
    parser = argparse.ArgumentParser(description="Document PSTN evidence")
    parser.add_argument("--evidence-dir", default="testlab/pstn-evidence",
                       help="Evidence directory")
    parser.add_argument("--output-dir", default="testlab/pstn-documentation",
                       help="Output directory")
    
    args = parser.parse_args()
    
    evidence_dir = Path(args.evidence_dir)
    output_dir = Path(args.output_dir)
    
    if not evidence_dir.exists():
        print(f"\n❌ Evidence directory not found: {evidence_dir}")
        print("   Run live_pstn_test.py first to generate evidence")
        return
    
    doc = create_documentation(evidence_dir, output_dir)
    
    if doc["production_ready"]:
        print("\n✅ Production gate CLEARED")
    else:
        print("\n⚠️ Production gate NOT CLEARED")
        print("   PHYSICAL_PSTN_EVIDENCE_REQUIRED")


if __name__ == "__main__":
    main()
