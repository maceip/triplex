"""Round 2: isolate the acoustic mechanism behind non-convergent cloning.

Round 1 ruled out reference-text mismatch, low level, and additive noise.
Only the real microphone capture failed, and normalizing it did not help.
This round tests the remaining suspects — room reverberation and the
band-limiting of a speaker/mic path — plus the fully conditioned capture.
"""

from __future__ import annotations

import json
import multiprocessing as mp
from datetime import datetime, timezone
from pathlib import Path

from diagnose_reference import TRUE_TEXT, WORK, run_trial


def main() -> int:
    trials = [
        ("H clean + REVERB", WORK / "clean_reverb.wav"),
        ("I clean + BANDLIMITED", WORK / "clean_bandlimited.wav"),
        ("J mic + full conditioning", WORK / "mic_conditioned.wav"),
    ]
    results = [run_trial(name, path, TRUE_TEXT) for name, path in trials]
    (WORK / "report_round2.json").write_text(
        json.dumps(
            {"created_utc": datetime.now(timezone.utc).isoformat(), "results": results},
            indent=2,
        )
        + "\n"
    )
    return 0


if __name__ == "__main__":
    mp.set_start_method("spawn")
    raise SystemExit(main())
