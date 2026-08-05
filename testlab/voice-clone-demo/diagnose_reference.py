"""Isolate why some reference recordings send Qwen3-TTS into a
non-converging generation.

Three candidate causes for the Pixel failure:
  1. reference text that does not match the spoken audio
  2. low capture level (mic capture measured ~10 dB below the clean file)
  3. low SNR / room noise (mic capture measured ~38 dB worse)

Each trial runs in its own subprocess with a hard wall-clock cap, so a wedged
generation is killed instead of poisoning the run.

    .venv/bin/python testlab/voice-clone-demo/diagnose_reference.py
"""

from __future__ import annotations

import json
import multiprocessing as mp
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "services"))

WORK = Path(__file__).resolve().parent / "artifacts" / "diagnose"
CLEAN = REPO / "testlab/voice-clone-demo/artifacts/smoke/reference_kokoro_af_heart.wav"
MIC = Path("/private/tmp/claude-501/-Users-mac-triplex/1fbd81a2-13ab-40dc-8b8c-e59bf8ad65b8/scratchpad/mic_ref.wav")

TRUE_TEXT = (
    "Triplex is a phone first voice agent. This recording is a synthetic "
    "reference sample generated for engineering validation of the voice "
    "cloning pipeline."
)
WRONG_TEXT = (
    "I consent to Triplex creating a clone of my voice from this recording, "
    "for my own use. Today I am setting up my voice profile, and this "
    "sentence is my reference sample."
)
TARGET = "The gate review moved to Thursday at four thirty."
CAP_SECONDS = 90


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as source:
        sr = source.getframerate()
        pcm = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
    return pcm.astype(np.float32), sr


def write_wav(path: Path, pcm: np.ndarray, sr: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(sr)
        sink.writeframes(np.clip(pcm, -32768, 32767).astype("<i2").tobytes())


def attenuate(pcm: np.ndarray, target_rms: float) -> np.ndarray:
    rms = float(np.sqrt((pcm**2).mean()))
    return pcm * (target_rms / max(rms, 1e-9))


def add_noise(pcm: np.ndarray, snr_db: float) -> np.ndarray:
    rms = float(np.sqrt((pcm**2).mean()))
    noise_rms = rms / (10 ** (snr_db / 20))
    rng = np.random.default_rng(7)
    return pcm + rng.normal(0, noise_rms, pcm.shape).astype(np.float32)


def _trial(reference: str, ref_text: str, queue: mp.Queue) -> None:
    from datetime import datetime as dt
    from voice_models.qwen3_tts_engine import Qwen3TTSEngine, VoiceCloneProfile

    engine = Qwen3TTSEngine()
    engine.initialize()
    profile = VoiceCloneProfile(
        profile_id="diagnose",
        speaker_id="diagnose",
        reference_audio=Path(reference),
        reference_text=ref_text,
        consent_id="diagnostic-no-human-subject",
        consent_verified_at=dt.now(timezone.utc),
    )
    engine.register_voice(profile)
    start = time.monotonic()
    import asyncio

    audio, sr = asyncio.run(engine.synthesize(TARGET, profile_id="diagnose"))
    queue.put({"seconds": time.monotonic() - start, "out_seconds": len(audio) / sr})


def run_trial(name: str, reference: Path, ref_text: str) -> dict:
    queue: mp.Queue = mp.Queue()
    proc = mp.Process(target=_trial, args=(str(reference), ref_text, queue))
    started = time.monotonic()
    proc.start()
    proc.join(CAP_SECONDS)
    if proc.is_alive():
        proc.kill()
        proc.join()
        result = {"trial": name, "outcome": "NON-CONVERGENT", "capped_at": CAP_SECONDS}
    elif queue.empty():
        result = {"trial": name, "outcome": "ERROR", "seconds": round(time.monotonic() - started, 1)}
    else:
        payload = queue.get()
        result = {
            "trial": name,
            "outcome": "ok",
            "synthesis_seconds": round(payload["seconds"], 1),
            "output_seconds": round(payload["out_seconds"], 2),
        }
    print(f"  {name}: {result.get('outcome')} "
          f"{result.get('synthesis_seconds', result.get('capped_at', ''))}s", flush=True)
    return result


def main() -> int:
    WORK.mkdir(parents=True, exist_ok=True)
    clean, sr = read_wav(CLEAN)
    mic, mic_sr = read_wav(MIC)

    # Derived variants isolating one factor at a time.
    quiet = WORK / "clean_quiet.wav"
    write_wav(quiet, attenuate(clean, 549.0), sr)  # clean, at the mic's level
    noisy = WORK / "clean_noisy.wav"
    write_wav(noisy, add_noise(clean, 25.5), sr)  # clean level, the mic's SNR
    mic_norm = WORK / "mic_normalized.wav"
    write_wav(mic_norm, attenuate(mic, 1698.0), mic_sr)  # mic, at the clean level

    trials = [
        ("A clean + matching text (baseline)", CLEAN, TRUE_TEXT),
        ("B clean + MISMATCHED text", CLEAN, WRONG_TEXT),
        ("C clean but quiet + matching text", quiet, TRUE_TEXT),
        ("D clean but noisy + matching text", noisy, TRUE_TEXT),
        ("E mic capture + matching text", MIC, TRUE_TEXT),
        ("F mic capture normalized + matching text", mic_norm, TRUE_TEXT),
        ("G mic capture + MISMATCHED text", MIC, WRONG_TEXT),
    ]

    print(f"Running {len(trials)} trials, {CAP_SECONDS}s cap each\n")
    results = [run_trial(name, path, text) for name, path, text in trials]

    report = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "cap_seconds": CAP_SECONDS,
        "target_text": TARGET,
        "results": results,
    }
    (WORK / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"\nReport: {WORK / 'report.json'}")
    return 0


if __name__ == "__main__":
    mp.set_start_method("spawn")
    raise SystemExit(main())
