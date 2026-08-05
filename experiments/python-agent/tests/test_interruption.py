"""Barge-in cancellation and spoken-context alignment tests."""

from __future__ import annotations

import asyncio
import struct
from collections.abc import AsyncIterator

from triplex.interruption.tear_down import TearDownCoordinator
from triplex.pipeline import PipelineConfig, VoicePipeline
from triplex.reasoning.vllm_engine import TokenChunk
from triplex.synthesis.kokoro_engine import SynthesizedAudioChunk


class ImmediateASR:
    async def transcribe(self, _audio: bytes, _sample_rate: int) -> str:
        return "start talking"


class AbortableReasoning:
    def __init__(self) -> None:
        self.aborted = False

    async def generate_stream(self, _prompt: str, _history: list[dict]):
        yield TokenChunk(text="one two three.", is_final=True, tokens_generated=3)

    async def abort(self) -> None:
        self.aborted = True


class SlowAnnotatedTTS:
    async def synthesize_stream_chunks(
        self, text: str
    ) -> AsyncIterator[SynthesizedAudioChunk]:
        for index in range(20):
            await asyncio.sleep(0.005)
            yield SynthesizedAudioChunk(
                pcm=b"\x01\x00" * 320,
                sample_rate=16000,
                text=("one " if index == 0 else "two three." if index == 1 else ""),
            )


async def barge_in_stream() -> AsyncIterator[bytes]:
    speech = struct.pack("<320h", *([9000] * 320))
    silence = b"\x00\x00" * 320
    for _ in range(3):
        yield speech
    for _ in range(2):
        yield silence
    for _ in range(4):
        await asyncio.sleep(0.008)
        yield silence
    yield speech


async def test_barge_in_aborts_generation_flushes_playout_and_aligns_history() -> None:
    reasoning = AbortableReasoning()
    pipeline = VoicePipeline(
        PipelineConfig(
            vad_backend="energy",
            min_speech_duration_ms=40,
            interruption_confirmation_ms=20,
            use_mock_asr=True,
            use_mock_llm=True,
        ),
        asr=ImmediateASR(),
        llm=reasoning,
        tts=SlowAnnotatedTTS(),
    )
    flushed: list[str] = []

    async def flush_playout(generation_id: str) -> int:
        flushed.append(generation_id)
        return 320

    pipeline.set_playout_flush(flush_playout)
    outputs = [output async for output in pipeline.process_audio_stream(barge_in_stream())]

    assert outputs
    assert reasoning.aborted
    assert len(flushed) == 1
    assert pipeline.last_interruption is not None
    assert pipeline.last_interruption.latency_ms < 50
    assistant_turns = [
        turn for turn in pipeline.conversation_history if turn["role"] == "assistant"
    ]
    assert assistant_turns == [
        {
            "role": "assistant",
            "content": "one",
            "generation_id": flushed[0],
        }
    ]


async def test_deadline_does_not_cancel_abort_or_remote_flush() -> None:
    abort_finished = asyncio.Event()
    flush_finished = asyncio.Event()

    async def abort_generation() -> None:
        await asyncio.sleep(0.02)
        abort_finished.set()

    async def flush_playout(_generation_id: str) -> int:
        await asyncio.sleep(0.02)
        flush_finished.set()
        return 17

    coordinator = TearDownCoordinator(
        flush_local=lambda _generation_id: None,
        abort_generation=abort_generation,
        flush_playout=flush_playout,
        deadline_ms=1.0,
    )
    result = await coordinator.trigger("generation-1", known_played_samples=3)

    assert result.played_samples == 3
    await asyncio.wait_for(abort_finished.wait(), timeout=0.1)
    await asyncio.wait_for(flush_finished.wait(), timeout=0.1)
