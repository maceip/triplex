"""Host-side TTS benchmark: real-time factor and first-chunk latency.

Phase 4 of FINAL_UNIFICATION.md. Establishes a reference on this machine and
validates the harness before the same measurement runs on the phone, which is
where the decision is actually made.

Decision rule (fixed in advance): real-time factor above 1.5 and first chunk
under 300 ms on device means synthesis ships on device.

    .venv/bin/python testlab/tts-bench/bench_onnx.py
"""

from __future__ import annotations

import glob
import json
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import onnxruntime as ort

OUT = Path(__file__).resolve().parent / "artifacts"
SAMPLE_RATE = 24_000
# Token counts standing in for short/medium/long agent turns. The frontend is
# phoneme-based; exact text does not matter for throughput.
TOKEN_LENGTHS = {"short": 32, "medium": 96, "long": 192}
RUNS = 5


def sessions(threads: int) -> tuple[ort.InferenceSession, ort.InferenceSession]:
    base = glob.glob(
        "/Users/mac/.cache/huggingface/hub/"
        "models--owensong--Inflect-Micro-v2-ONNX/snapshots/*/onnx"
    )[0]
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    make = lambda name: ort.InferenceSession(  # noqa: E731
        f"{base}/{name}.onnx", options, providers=["CPUExecutionProvider"]
    )
    return make("duration"), make("decode")


def synth(duration_s, decode_s, n_tokens: int) -> tuple[float, float, int]:
    """Returns (duration-stage seconds, decode-stage seconds, samples)."""
    rng = np.random.default_rng(0)
    tokens = rng.integers(1, 100, size=(1, n_tokens)).astype(np.int64)

    t0 = time.perf_counter()
    m_p, logs_p, y_mask = duration_s.run(
        None,
        {
            "tokens": tokens,
            "lengths": np.array([n_tokens], dtype=np.int64),
            "length_scale": np.array(1.0, dtype=np.float32),
        },
    )
    t1 = time.perf_counter()

    zp = rng.standard_normal(m_p.shape).astype(np.float32)
    (wav,) = decode_s.run(
        None,
        {
            "m_p_exp": m_p,
            "logs_p_exp": logs_p,
            "y_mask": y_mask,
            "zp_noise": zp,
            "noise_scale": np.array(0.667, dtype=np.float32),
        },
    )
    t2 = time.perf_counter()
    return t1 - t0, t2 - t1, wav.size


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    for threads in (1, 4):
        duration_s, decode_s = sessions(threads)
        synth(duration_s, decode_s, 32)  # warm up
        for label, n in TOKEN_LENGTHS.items():
            trials = [synth(duration_s, decode_s, n) for _ in range(RUNS)]
            dur = statistics.median(t[0] for t in trials)
            dec = statistics.median(t[1] for t in trials)
            samples = trials[0][2]
            audio_s = samples / SAMPLE_RATE
            total = dur + dec
            row = {
                "threads": threads,
                "turn": label,
                "tokens": n,
                "audio_seconds": round(audio_s, 3),
                "synth_seconds": round(total, 4),
                "rtf": round(audio_s / total, 2),
                "duration_stage_ms": round(dur * 1000, 1),
                "decode_stage_ms": round(dec * 1000, 1),
            }
            results.append(row)
            print(
                f"  {threads}t {label:6s} {audio_s:5.2f}s audio in "
                f"{total*1000:7.1f}ms  RTF {row['rtf']:5.2f}"
            )

    report = {
        "model": "owensong/Inflect-Micro-v2-ONNX",
        "host": "Apple Silicon, ONNX Runtime CPU provider",
        "note": (
            "Host reference only. The decision rule applies to on-device "
            "measurement; this validates the harness and bounds expectations."
        ),
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "results": results,
    }
    (OUT / "inflect_host.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"\nReport: {OUT / 'inflect_host.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
