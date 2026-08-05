"""Voice-clone backend service (REMOTE preparation + REMOTE_TTS synthesis).

Wraps the proven consent-gated Qwen3-TTS engine behind HTTP for the Triplex
gateway. This process is backend infrastructure only — the user-facing
application is the Android app. It stands in for the future cloud voice
service; on-device synthesis remains the preferred end state once a
maintained mobile runtime for zero-shot cloning exists (placement rules:
offload is explicit and recorded, never a hidden default).

Run on the host (needs the repo .venv for torch/MPS):

    .venv/bin/python -m uvicorn service:app --host 0.0.0.0 --port 8801 \
        --app-dir services/voice-clone

Profiles persist under services/voice-clone/profiles/<profile_id>/ as the
uploaded reference plus consent metadata; clone prompts are rebuilt lazily
after restart. Deleting a profile directory (or DELETE /profiles/{id})
revokes it.
"""

from __future__ import annotations

import asyncio
import io
import json
import os
import sys
import threading
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "src"))

from triplex.synthesis.qwen3_tts_engine import (  # noqa: E402
    Qwen3TTSEngine,
    VoiceCloneProfile,
)

PROFILES_DIR = Path(__file__).resolve().parent / "profiles"
SYNTHESIS_TIMEOUT_SECONDS = 120.0
# A profile is only "ready" once it has actually produced audio. Measured
# behaviour: a good reference synthesizes a short line in a few seconds, while
# a reference the speaker encoder cannot place runs indefinitely. Heuristics on
# the audio (level, SNR, text match) did not predict this — see
# testlab/voice-clone-demo/diagnose_reference.py — so we verify empirically.
VERIFY_TIMEOUT_SECONDS = 45.0
VERIFY_TEXT = "Testing my new voice."
PLACEMENT = "REMOTE_TTS"
PLACEMENT_REASON = (
    "no maintained on-device zero-shot cloning TTS for Android exists yet; "
    "explicit cloud offload per placement rules"
)

app = FastAPI(title="Triplex Voice Clone Service", version="0.1.0")
_engine = Qwen3TTSEngine()
_engine_lock = threading.Lock()


def _schedule_process_recycle(delay_seconds: float = 1.0) -> None:
    """Exit shortly after responding so the supervisor can restart cleanly."""

    def _bail() -> None:
        time.sleep(delay_seconds)
        os._exit(75)  # EX_TEMPFAIL: transient, restart me

    threading.Thread(target=_bail, daemon=True).start()


def _profile_dir(profile_id: str) -> Path:
    if not profile_id or any(c in profile_id for c in "/\\.."):
        raise HTTPException(status_code=400, detail="Invalid profile id")
    return PROFILES_DIR / profile_id


def _load_registered(profile_id: str) -> None:
    """Rebuild the clone prompt for a persisted profile if not in memory."""
    if profile_id in _engine.registered_profile_ids:
        return
    directory = _profile_dir(profile_id)
    meta_path = directory / "profile.json"
    if not meta_path.is_file():
        raise HTTPException(status_code=404, detail="Unknown voice profile")
    meta = json.loads(meta_path.read_text())
    profile = VoiceCloneProfile(
        profile_id=profile_id,
        speaker_id=meta["speaker_id"],
        reference_audio=directory / "reference.wav",
        reference_text=meta["reference_text"],
        consent_id=meta["consent_id"],
        consent_verified_at=datetime.fromisoformat(meta["consent_verified_at"]),
    )
    with _engine_lock:
        _engine.register_voice(profile)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "model": _engine.config.model_name,
        "revision": _engine.config.model_revision,
        "placement": PLACEMENT,
        "placement_reason": PLACEMENT_REASON,
    }


@app.post("/profiles/{profile_id}")
async def create_profile(
    profile_id: str,
    speaker_id: str = Form(...),
    reference_text: str = Form(...),
    consent_id: str = Form(...),
    consent_verified_at: str = Form(...),
    reference: UploadFile = File(...),
) -> dict:
    directory = _profile_dir(profile_id)
    directory.mkdir(parents=True, exist_ok=True)
    reference_path = directory / "reference.wav"
    reference_path.write_bytes(await reference.read())

    try:
        consent_dt = datetime.fromisoformat(consent_verified_at)
        profile = VoiceCloneProfile(
            profile_id=profile_id,
            speaker_id=speaker_id,
            reference_audio=reference_path,
            reference_text=reference_text,
            consent_id=consent_id,
            consent_verified_at=consent_dt,
        )
        with _engine_lock:
            registered = _engine.register_voice(profile)
    except (ValueError, KeyError) as exc:
        reference_path.unlink(missing_ok=True)
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    # Verification pass: prove this reference can actually speak before the
    # product calls the voice ready.
    try:
        await asyncio.wait_for(
            _engine.synthesize(VERIFY_TEXT, profile_id=profile_id),
            timeout=VERIFY_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        reference_path.unlink(missing_ok=True)
        _schedule_process_recycle()
        raise HTTPException(
            status_code=422,
            detail=(
                "that recording did not produce a usable voice; please record "
                "again in a quieter room, speaking directly into the phone"
            ),
        ) from exc
    except Exception as exc:  # noqa: BLE001 - report any failure honestly
        reference_path.unlink(missing_ok=True)
        raise HTTPException(
            status_code=422,
            detail=f"voice preparation failed: {exc}",
        ) from exc

    (directory / "profile.json").write_text(
        json.dumps(
            {
                "speaker_id": speaker_id,
                "reference_text": reference_text,
                "consent_id": consent_id,
                "consent_verified_at": consent_dt.isoformat(),
                "reference_sha256": registered.reference_sha256,
                "duration_seconds": registered.duration_seconds,
                "registered_utc": datetime.now(timezone.utc).isoformat(),
                "placement": PLACEMENT,
                "placement_reason": PLACEMENT_REASON,
            },
            indent=2,
        )
        + "\n"
    )
    return {
        "profile_id": profile_id,
        "reference_sha256": registered.reference_sha256,
        "duration_seconds": registered.duration_seconds,
        "synthesis_ready": True,
        "placement": PLACEMENT,
    }


@app.get("/profiles/{profile_id}")
def profile_status(profile_id: str) -> dict:
    directory = _profile_dir(profile_id)
    meta_path = directory / "profile.json"
    if not meta_path.is_file():
        raise HTTPException(status_code=404, detail="Unknown voice profile")
    meta = json.loads(meta_path.read_text())
    return {
        "profile_id": profile_id,
        "synthesis_ready": True,
        "reference_sha256": meta["reference_sha256"],
        "duration_seconds": meta["duration_seconds"],
        "placement": PLACEMENT,
    }


@app.delete("/profiles/{profile_id}")
def revoke_profile(profile_id: str) -> dict:
    directory = _profile_dir(profile_id)
    if not directory.is_dir():
        raise HTTPException(status_code=404, detail="Unknown voice profile")
    for child in directory.iterdir():
        child.unlink()
    directory.rmdir()
    return {"profile_id": profile_id, "revoked": True}


@app.post("/profiles/{profile_id}/synthesize")
async def synthesize(profile_id: str, body: dict) -> Response:
    text = str(body.get("text", "")).strip()
    if not text or len(text) > 500:
        raise HTTPException(status_code=422, detail="text must be 1-500 characters")
    _load_registered(profile_id)
    try:
        # A degraded reference can push the model into a very long or runaway
        # generation. Bound it so the caller gets an error instead of a hang.
        audio, sample_rate = await asyncio.wait_for(
            _engine.synthesize(text, profile_id=profile_id),
            timeout=SYNTHESIS_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        # asyncio cannot cancel the worker thread, and that thread still holds
        # the model lock, so every later request would queue behind a
        # generation that never converges. Answer this caller honestly, then
        # recycle the process; the supervisor restarts it and profiles are
        # rebuilt lazily from disk.
        _schedule_process_recycle()
        raise HTTPException(
            status_code=504,
            detail=(
                f"synthesis exceeded {SYNTHESIS_TIMEOUT_SECONDS}s; the reference "
                "recording may be too noisy to clone reliably"
            ),
        ) from exc
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(sample_rate)
        sink.writeframes(pcm.tobytes())
    return Response(
        content=buffer.getvalue(),
        media_type="audio/wav",
        headers={
            "X-Placement": PLACEMENT,
            "X-Model-Revision": _engine.config.model_revision,
        },
    )
