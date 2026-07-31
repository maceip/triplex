"""Build the minimal, reviewable SIP Lambda zip used by CloudFormation."""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


def build(output: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    sources = {
        "handler.py": root / "src/triplex/transport/sip_media_app/handler.py",
        "chime_server.py": root / "src/triplex/transport/chime_server.py",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for archive_name, source in sources.items():
            bundle.write(source, archive_name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    build(arguments.output.resolve())


if __name__ == "__main__":
    main()
