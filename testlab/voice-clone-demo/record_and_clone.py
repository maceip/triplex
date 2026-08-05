"""Local consented voice-clone prototype (interactive, Mac-only).

The capture-to-playback reveal: you record yourself speaking the consent
statement below — that recording IS both the auditable consent and the
cloning reference — then the pinned Qwen3-TTS model speaks a line you never
said, in your voice, and plays it back.

Honest scope: this is an isolated host-side prototype. It is NOT the Android
runtime, NOT a live call, and produces no claim beyond "a consented local
clone was synthesized on this machine".

Run:  .venv/bin/python testlab/voice-clone-demo/record_and_clone.py
      (press Enter to start/stop the recording; needs mic permission)

Artifacts land in testlab/voice-clone-demo/artifacts/consented/<timestamp>/:
reference.wav (your consent recording), cloned_output.wav, manifest.json.
Delete the directory to revoke — nothing is uploaded anywhere.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "services"))

from voice_models.qwen3_tts_engine import (  # noqa: E402
    Qwen3TTSEngine,
    VoiceCloneProfile,
)

CONSENT_STATEMENT = (
    "I consent to Triplex creating a clone of my voice from this recording, "
    "for my own use, on this device. Today I am testing the local voice "
    "cloning prototype, and this sentence is my reference sample."
)

DEFAULT_NOVEL_TEXT = (
    "This is my cloned voice speaking a sentence I never actually said. "
    "The kitchen said the reservation moved to eight fifteen, and the "
    "confirmation number ends in four two seven."
)


def record_wav(path: Path, sample_rate: int = 24_000) -> float:
    """Record mono 16-bit PCM from the default mic via ffmpeg/avfoundation.

    First run triggers the macOS microphone-permission prompt for your
    terminal; approve it and rerun if the capture comes back empty.
    """
    print("\n=== CONSENT + REFERENCE RECORDING ===")
    print("Read this aloud, naturally, in one take (~10 seconds):\n")
    print(f'  "{CONSENT_STATEMENT}"\n')
    input("Press Enter to START recording ...")
    last_error = ""
    for device in (":default", ":0"):
        process = subprocess.Popen(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "avfoundation", "-i", device,
                "-ac", "1", "-ar", str(sample_rate), "-sample_fmt", "s16",
                "-y", str(path),
            ],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        time.sleep(0.7)
        if process.poll() is not None:  # immediate failure: try next device
            last_error = (process.stderr.read() if process.stderr else b"").decode()
            continue
        input("Recording... press Enter to STOP.")
        assert process.stdin is not None
        process.stdin.write(b"q")
        process.stdin.close()
        process.wait(timeout=10)
        with wave.open(str(path), "rb") as check:
            duration = check.getnframes() / check.getframerate()
        print(f"Captured {duration:.1f}s -> {path.name}")
        return duration
    raise RuntimeError(f"Microphone capture failed: {last_error.strip()}")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_wav(path: Path, pcm16: np.ndarray, sample_rate: int) -> None:
    with wave.open(str(path), "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(sample_rate)
        sink.writeframes(pcm16.astype("<i2").tobytes())


def main() -> int:
    novel_text = " ".join(sys.argv[1:]).strip() or DEFAULT_NOVEL_TEXT
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = Path(__file__).resolve().parent / "artifacts" / "consented" / stamp
    out_dir.mkdir(parents=True, exist_ok=True)
    reference = out_dir / "reference.wav"
    output = out_dir / "cloned_output.wav"

    duration = record_wav(reference)
    if not 3.0 <= duration <= 30.0:
        print(f"Reference must be 3-30s (got {duration:.1f}s). Try again.")
        return 1

    print("\nLoading pinned Qwen3-TTS model ...")
    engine = Qwen3TTSEngine()
    engine.initialize()

    consent_time = datetime.now(timezone.utc)
    profile = VoiceCloneProfile(
        profile_id=f"consented-{stamp}",
        speaker_id="local-user",
        reference_audio=reference,
        reference_text=CONSENT_STATEMENT,
        consent_id=f"spoken-consent-{stamp}",
        consent_verified_at=consent_time,
    )
    print("Building voice-clone prompt from your recording ...")
    registered = engine.register_voice(profile)

    print(f'\nSynthesizing (never spoken by you):\n  "{novel_text}"\n')
    import asyncio

    t0 = time.monotonic()
    audio, sample_rate = asyncio.run(
        engine.synthesize(novel_text, profile_id=profile.profile_id)
    )
    elapsed = time.monotonic() - t0
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    write_wav(output, pcm, sample_rate)
    print(f"Synthesized {len(pcm) / sample_rate:.1f}s in {elapsed:.1f}s")

    manifest = {
        "label": "local consented voice-clone prototype",
        "consent": {
            "mechanism": "spoken statement recorded as the reference sample",
            "statement": CONSENT_STATEMENT,
            "recorded_utc": consent_time.isoformat(),
            "revocation": "delete this directory; nothing leaves this machine",
        },
        "model": {
            "name": engine.config.model_name,
            "revision": engine.config.model_revision,
        },
        "reference": {
            "file": reference.name,
            "sha256": registered.reference_sha256,
            "duration_seconds": round(registered.duration_seconds, 3),
        },
        "output": {
            "file": output.name,
            "sha256": sha256(output),
            "sample_rate_hz": sample_rate,
            "text": novel_text,
        },
        "not_claimed": [
            "Android on-device synthesis",
            "live PSTN call in a cloned voice",
            "signed/encrypted voice artifact lifecycle",
        ],
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    print("\n=== REVEAL ===")
    subprocess.run(["afplay", str(reference)], check=False)
    time.sleep(0.4)
    subprocess.run(["afplay", str(output)], check=False)
    print(f"\nArtifacts: {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
