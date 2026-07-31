"""Consent, reference validation, and explicit dual-engine routing tests."""

from __future__ import annotations

import wave
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import pytest

from triplex.synthesis.dual_router import DualVoiceRouter, VoiceMode
from triplex.synthesis.kokoro_engine import SynthesizedAudioChunk
from triplex.synthesis.qwen3_tts_engine import Qwen3TTSConfig, Qwen3TTSEngine, VoiceCloneProfile


class FakeQwenModel:
    def __init__(self) -> None:
        self.prompt_calls: list[dict[str, object]] = []
        self.generation_calls: list[dict[str, object]] = []

    def create_voice_clone_prompt(self, **kwargs: object) -> list[str]:
        self.prompt_calls.append(kwargs)
        return ["cached-prompt"]

    def generate_voice_clone(self, **kwargs: object) -> tuple[list[np.ndarray], int]:
        self.generation_calls.append(kwargs)
        return [np.linspace(-0.5, 0.5, 4800, dtype=np.float32)], 24000


class RecordingBrandedEngine:
    def __init__(self) -> None:
        self.texts: list[str] = []

    async def synthesize_stream_chunks(self, text: str):
        self.texts.append(text)
        yield SynthesizedAudioChunk(b"\x01\x00" * 10, 16000, text)


def _write_reference(path: Path, seconds: float = 3.5) -> None:
    sample_rate = 16000
    samples = (np.sin(np.arange(int(sample_rate * seconds)) * 0.03) * 8000).astype("<i2")
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(sample_rate)
        target.writeframes(samples.tobytes())


def _profile(path: Path) -> VoiceCloneProfile:
    return VoiceCloneProfile(
        profile_id="customer-voice",
        speaker_id="speaker-42",
        reference_audio=path,
        reference_text="This is my authorized reference recording.",
        consent_id="consent-2026-07-31",
        consent_verified_at=datetime(2026, 7, 31, tzinfo=UTC),
    )


@pytest.mark.asyncio
async def test_clone_profile_is_validated_cached_and_chunked(tmp_path: Path) -> None:
    reference = tmp_path / "reference.wav"
    _write_reference(reference)
    model = FakeQwenModel()
    engine = Qwen3TTSEngine(Qwen3TTSConfig(chunk_duration_ms=100), model=model)

    registered = engine.register_voice(_profile(reference))
    engine.select_voice("customer-voice")
    chunks = [chunk async for chunk in engine.synthesize_stream_chunks("hello there")]

    assert len(registered.reference_sha256) == 64
    assert registered.duration_seconds == pytest.approx(3.5)
    assert len(model.prompt_calls) == 1
    assert len(model.generation_calls) == 1
    assert b"".join(chunk.pcm for chunk in chunks)
    assert " ".join(chunk.text for chunk in chunks if chunk.text) == "hello there"

    assert engine.register_voice(_profile(reference)) is registered
    conflicting = replace(_profile(reference), consent_id="different-consent")
    with pytest.raises(ValueError, match="already registered"):
        engine.register_voice(conflicting)


def test_clone_profile_requires_auditable_consent(tmp_path: Path) -> None:
    reference = tmp_path / "reference.wav"
    _write_reference(reference)

    with pytest.raises(ValueError, match="consent_id"):
        VoiceCloneProfile(
            profile_id="voice",
            speaker_id="speaker",
            reference_audio=reference,
            reference_text="Reference text",
            consent_id="",
            consent_verified_at=datetime.now(UTC),
        )


@pytest.mark.asyncio
async def test_router_never_silently_falls_back_from_clone_mode(tmp_path: Path) -> None:
    branded = RecordingBrandedEngine()
    router = DualVoiceRouter(branded)
    with pytest.raises(RuntimeError, match="not configured"):
        router.use_cloned_voice("missing")

    reference = tmp_path / "reference.wav"
    _write_reference(reference)
    clone = Qwen3TTSEngine(model=FakeQwenModel())
    router = DualVoiceRouter(branded, clone)
    router.use_cloned_voice(_profile(reference))
    chunks = [chunk async for chunk in router.synthesize_stream_chunks("cloned")]

    assert router.mode is VoiceMode.CLONED
    assert chunks
    assert branded.texts == []
