"""Executable regression tests for the local Triplex pipeline."""

from __future__ import annotations

import struct
from collections.abc import AsyncIterator
from types import SimpleNamespace

import numpy as np
import pytest
import torch

from triplex.asr.whisper_engine import ASRConfig, WhisperASR
from triplex.pipeline import PipelineConfig, VoicePipeline
from triplex.synthesis.kokoro_engine import VoiceProfile, _as_numpy_audio


class DeterministicTTS:
    """Small audio sink used to isolate pipeline orchestration in unit tests."""

    def __init__(self) -> None:
        self.texts: list[str] = []

    async def synthesize_stream(self, text: str) -> AsyncIterator[bytes]:
        assert text
        self.texts.append(text)
        yield b"\x01\x02" * 64


async def pcm_stream() -> AsyncIterator[bytes]:
    """Yield loud PCM speech frames followed by enough silence to close a turn."""
    speech = struct.pack("<320h", *([8000] * 320))
    silence = b"\x00\x00" * 320

    for _ in range(15):
        yield speech
    for _ in range(5):
        yield silence


@pytest.mark.asyncio
async def test_pipeline_emits_audio_and_records_a_turn() -> None:
    pipeline = VoicePipeline(
        PipelineConfig(
            vad_backend="energy",
            min_speech_duration_ms=60,
            use_mock_asr=True,
            use_mock_llm=True,
        )
    )
    tts = DeterministicTTS()
    pipeline.tts = tts

    responses = [item async for item in pipeline.process_audio_stream(pcm_stream())]

    assert len(responses) == 2
    assert all(audio == b"\x01\x02" * 64 for audio, _metrics in responses)
    assert responses[-1][1].total_ms > 0
    assert tts.texts == ["I understand.", "Let me help you with that."]
    assert [turn["role"] for turn in pipeline._conversation_history] == ["user", "assistant"]


def test_voice_profile_preserves_description() -> None:
    profile = VoiceProfile.branded("Reception", "af_heart", "Warm voice")

    assert profile.description == "Warm voice"


def test_kokoro_tensor_output_is_normalized() -> None:
    audio = _as_numpy_audio(torch.tensor([0.25, -0.25], dtype=torch.float32))

    assert isinstance(audio, np.ndarray)
    np.testing.assert_allclose(audio, np.array([0.25, -0.25], dtype=np.float32))


@pytest.mark.asyncio
async def test_whisper_passes_vad_settings_through_supported_parameter() -> None:
    class RecordingModel:
        def __init__(self) -> None:
            self.kwargs: dict[str, object] = {}

        def transcribe(self, _audio: np.ndarray, **kwargs: object):
            self.kwargs = kwargs
            segments = [SimpleNamespace(text=" test")]
            return segments, SimpleNamespace(language_probability=0.99)

    engine = WhisperASR(ASRConfig(min_silence_duration_ms=375))
    model = RecordingModel()
    engine._model = model

    transcript = await engine.transcribe(b"\x00\x00" * 320)

    assert transcript == "test"
    assert model.kwargs["vad_parameters"] == {"min_silence_duration_ms": 375}
