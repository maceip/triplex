"""Bounded voice-clone smoke run: synthetic reference, real Qwen inference.

Proves the pinned Qwen3-TTS zero-shot cloning pipeline executes end to end on
this machine. The reference speaker is SYNTHETIC (Kokoro af_heart) with fully
known reference text, so no human voice or consent is involved; the honest
label for the produced artifact is "synthetic-reference clone smoke run".
Personal-voice demos go through record_and_clone.py, which captures spoken
consent.

Run:  .venv/bin/python testlab/voice-clone-demo/smoke_run.py
"""

from __future__ import annotations

import hashlib
import json
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "src"))

from triplex.synthesis.qwen3_tts_engine import (  # noqa: E402
    Qwen3TTSEngine,
    VoiceCloneProfile,
)

OUT_DIR = Path(__file__).resolve().parent / "artifacts" / "smoke"

REFERENCE_TEXT = (
    "Triplex is a phone first voice agent. This recording is a synthetic "
    "reference sample generated for engineering validation of the voice "
    "cloning pipeline."
)
# Never-before-spoken by the reference speaker; deliberately novel content.
NOVEL_TEXT = (
    "The quarterly gate review moved to Thursday at four thirty. Bring the "
    "latency evidence and the signed artifact checklist."
)


def write_wav(path: Path, pcm16: np.ndarray, sample_rate: int) -> None:
    with wave.open(str(path), "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(sample_rate)
        sink.writeframes(pcm16.astype("<i2").tobytes())


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate_reference(path: Path) -> float:
    from kokoro import KPipeline

    print("[1/4] Generating synthetic reference with Kokoro af_heart ...")
    pipeline = KPipeline(lang_code="a", repo_id="hexgrad/Kokoro-82M")
    segments = [audio for _, _, audio in pipeline(REFERENCE_TEXT, voice="af_heart")]
    audio = np.concatenate([np.asarray(s, dtype=np.float32) for s in segments])
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    write_wav(path, pcm, 24_000)
    duration = len(pcm) / 24_000
    print(f"      reference: {duration:.2f}s, {path.name}")
    return duration


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    reference_path = OUT_DIR / "reference_kokoro_af_heart.wav"
    output_path = OUT_DIR / "cloned_output.wav"
    manifest_path = OUT_DIR / "manifest.json"

    t0 = time.monotonic()
    ref_duration = generate_reference(reference_path)

    print("[2/4] Loading pinned Qwen3-TTS model (first load takes a while) ...")
    engine = Qwen3TTSEngine()
    t_load0 = time.monotonic()
    engine.initialize()
    t_load = time.monotonic() - t_load0
    print(f"      model ready in {t_load:.1f}s")

    print("[3/4] Registering synthetic voice profile + building clone prompt ...")
    profile = VoiceCloneProfile(
        profile_id="smoke-synthetic-af-heart",
        speaker_id="kokoro-af_heart-synthetic",
        reference_audio=reference_path,
        reference_text=REFERENCE_TEXT,
        consent_id="synthetic-reference-no-human-subject",
        consent_verified_at=datetime.now(timezone.utc),
    )
    t_reg0 = time.monotonic()
    registered = engine.register_voice(profile)
    t_reg = time.monotonic() - t_reg0
    print(f"      prompt built in {t_reg:.1f}s (ref sha256 {registered.reference_sha256[:16]}…)")

    print("[4/4] Synthesizing never-before-spoken line in the cloned voice ...")
    import asyncio

    t_syn0 = time.monotonic()
    audio, sample_rate = asyncio.run(
        engine.synthesize(NOVEL_TEXT, profile_id=profile.profile_id)
    )
    t_syn = time.monotonic() - t_syn0
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    write_wav(output_path, pcm, sample_rate)
    out_duration = len(pcm) / sample_rate
    print(f"      output: {out_duration:.2f}s @ {sample_rate} Hz in {t_syn:.1f}s")

    manifest = {
        "label": "synthetic-reference clone smoke run",
        "claim": (
            "Real Qwen3-TTS zero-shot inference executed locally; the cloned "
            "speaker is a synthetic Kokoro voice, not a human."
        ),
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "model": {
            "name": engine.config.model_name,
            "revision": engine.config.model_revision,
        },
        "reference": {
            "file": reference_path.name,
            "sha256": sha256(reference_path),
            "duration_seconds": round(ref_duration, 3),
            "text": REFERENCE_TEXT,
            "speaker": "kokoro af_heart (synthetic)",
        },
        "output": {
            "file": output_path.name,
            "sha256": sha256(output_path),
            "duration_seconds": round(out_duration, 3),
            "sample_rate_hz": sample_rate,
            "text": NOVEL_TEXT,
        },
        "timings_seconds": {
            "model_load": round(t_load, 2),
            "prompt_build": round(t_reg, 2),
            "synthesis": round(t_syn, 2),
            "total": round(time.monotonic() - t0, 2),
        },
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"\nManifest: {manifest_path}")
    print("SMOKE RUN COMPLETE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
