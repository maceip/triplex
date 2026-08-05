# SUPERSEDED-BY: apps/android/agent (agent-core::session)
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Concurrent full-duplex Triplex voice pipeline.

Incoming audio remains live while LLM and TTS tasks run, which is required for
real barge-in.  Generated PCM is placed on an interruptible queue; the Chime
output acknowledges actual AudioWorklet playout for conversation-state
alignment.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from collections import deque
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass, replace
from typing import Any

import numpy as np

from .asr.whisper_engine import ASRConfig, MockASR, WhisperASR
from .audio.vad import VoiceActivityDetector, VoiceState
from .interruption.state_alignment import SpokenTextTracker
from .interruption.tear_down import InterruptionResult, TearDownCoordinator
from .reasoning.vllm_engine import MockReasoningEngine, VLLMEngine
from .synthesis.kokoro_engine import KokoroEngine, SynthesizedAudioChunk
from .transport.audio_buffer import resample
from .transport.echo_canceller import EchoCanceller, EchoCancellerConfig


@dataclass(frozen=True)
class PipelineConfig:
    """Runtime and latency contract for a voice pipeline."""

    target_total_latency_ms: int = 300
    target_reflex_latency_ms: int = 100
    target_synthesis_latency_ms: int = 150
    target_interruption_latency_ms: int = 50
    sample_rate: int = 16000
    output_sample_rate: int = 16000
    chunk_duration_ms: int = 20
    vad_backend: str = "silero"
    speech_threshold: float = 0.5
    min_speech_duration_ms: int = 250
    interruption_confirmation_ms: int = 20
    enable_aec: bool = True
    echo_filter_length_ms: int = 80
    echo_delay_ms: int = 0
    use_mock_llm: bool = False
    use_mock_asr: bool = False
    asr_model: str = "small.en"
    max_history_turns: int = 10


@dataclass(frozen=True)
class LatencyMetrics:
    """Latency measurements for the current response."""

    vad_ms: float = 0.0
    asr_ms: float = 0.0
    llm_first_token_ms: float = 0.0
    tts_first_chunk_ms: float = 0.0
    total_ms: float = 0.0
    interruption_ms: float = 0.0

    def __str__(self) -> str:
        return (
            f"VAD: {self.vad_ms:.1f}ms | ASR: {self.asr_ms:.1f}ms | "
            f"LLM: {self.llm_first_token_ms:.1f}ms | "
            f"TTS: {self.tts_first_chunk_ms:.1f}ms | Total: {self.total_ms:.1f}ms"
        )


@dataclass(frozen=True)
class PipelineOutput:
    """One interruptible PCM output frame and its alignment metadata."""

    audio: bytes
    metrics: LatencyMetrics
    generation_id: str
    sequence: int
    text: str
    sample_rate: int

    def __iter__(self) -> Iterator[bytes | LatencyMetrics]:
        """Keep ``audio, metrics = output`` compatibility for existing callers."""
        yield self.audio
        yield self.metrics

    def __getitem__(self, index: int) -> bytes | LatencyMetrics:
        if index == 0:
            return self.audio
        if index == 1:
            return self.metrics
        raise IndexError(index)


class _InterruptibleOutputQueue:
    def __init__(self) -> None:
        self._items: deque[PipelineOutput] = deque()
        self._condition = asyncio.Condition()
        self._closed = False

    async def put(self, item: PipelineOutput) -> None:
        async with self._condition:
            if self._closed:
                return
            self._items.append(item)
            self._condition.notify()

    async def get(self) -> PipelineOutput | None:
        async with self._condition:
            await self._condition.wait_for(lambda: bool(self._items) or self._closed)
            if self._items:
                return self._items.popleft()
            return None

    def flush(self, generation_id: str) -> None:
        self._items = deque(
            item for item in self._items if item.generation_id != generation_id
        )

    async def close(self) -> None:
        async with self._condition:
            self._closed = True
            self._condition.notify_all()


class VoicePipeline:
    """Streaming VAD → ASR → LLM → TTS pipeline with real barge-in."""

    def __init__(
        self,
        config: PipelineConfig | None = None,
        *,
        vad: Any | None = None,
        asr: Any | None = None,
        llm: Any | None = None,
        tts: Any | None = None,
        echo_canceller: EchoCanceller | None = None,
    ) -> None:
        self.config = config or PipelineConfig()
        self.vad = vad or VoiceActivityDetector(backend=self.config.vad_backend)
        self.tts = tts or KokoroEngine()
        self.llm = llm or (
            MockReasoningEngine() if self.config.use_mock_llm else VLLMEngine()
        )
        self.asr = asr or (
            MockASR()
            if self.config.use_mock_asr
            else WhisperASR(ASRConfig(model_size=self.config.asr_model))
        )
        self.echo_canceller = echo_canceller or (
            EchoCanceller(
                EchoCancellerConfig(
                    sample_rate=self.config.sample_rate,
                    frame_duration_ms=self.config.chunk_duration_ms,
                    filter_length_ms=self.config.echo_filter_length_ms,
                    delay_ms=self.config.echo_delay_ms,
                )
            )
            if self.config.enable_aec
            else None
        )

        self._conversation_history: list[dict[str, str]] = []
        self._output_queue = _InterruptibleOutputQueue()
        self._alignment = SpokenTextTracker()
        self._generation_task: asyncio.Task[None] | None = None
        self._current_generation_id: str | None = None
        self._interrupted = False
        self._stream_running = False
        self._playout_flush: Callable[[str], Awaitable[int]] | None = None
        self._last_interruption: InterruptionResult | None = None

    @property
    def conversation_history(self) -> list[dict[str, str]]:
        return [dict(turn) for turn in self._conversation_history]

    @property
    def last_interruption(self) -> InterruptionResult | None:
        return self._last_interruption

    def set_playout_flush(
        self, callback: Callable[[str], Awaitable[int]] | None
    ) -> None:
        """Register the output device's immediate generation flush callback."""
        self._playout_flush = callback

    def initialize(self) -> None:
        """Load heavyweight components before accepting a call."""
        if not self.config.use_mock_llm:
            initialize_llm = getattr(self.llm, "initialize", None)
            if callable(initialize_llm):
                initialize_llm()
        initialize_tts = getattr(self.tts, "initialize", None)
        if callable(initialize_tts):
            initialize_tts()

    async def process_audio_stream(
        self, audio_stream: AsyncIterator[bytes]
    ) -> AsyncIterator[PipelineOutput]:
        """Process microphone audio while yielding concurrent response PCM."""
        if self._stream_running:
            raise RuntimeError("a VoicePipeline instance cannot process concurrent calls")
        self._stream_running = True
        self._output_queue = _InterruptibleOutputQueue()
        consumer = asyncio.create_task(
            self._consume_input(audio_stream), name="triplex-audio-ingress"
        )
        try:
            while True:
                output = await self._output_queue.get()
                if output is None:
                    break
                yield output
            await consumer
        finally:
            if not consumer.done():
                consumer.cancel()
                await asyncio.gather(consumer, return_exceptions=True)
            self._stream_running = False

    def push_echo_reference(self, pcm: bytes) -> None:
        """Record PCM as it is committed to the Chime playout device."""
        if self.echo_canceller is not None:
            self.echo_canceller.push_reference(pcm)

    def acknowledge_played(self, generation_id: str, played_samples: int) -> None:
        """Record cumulative samples acknowledged by the output AudioWorklet."""
        self._alignment.acknowledge(generation_id, played_samples)

    async def interrupt(self) -> InterruptionResult | None:
        """Public barge-in trigger for transport/reflex integrations."""
        return await self._handle_interruption()

    async def _consume_input(self, audio_stream: AsyncIterator[bytes]) -> None:
        audio_buffer = bytearray()
        speech_start: float | None = None
        silence_duration_ms = 0.0
        overlap_speech_ms = 0.0

        try:
            async for raw_chunk in audio_stream:
                chunk_time = time.perf_counter()
                chunk = (
                    self.echo_canceller.process(raw_chunk)
                    if self.echo_canceller is not None
                    else raw_chunk
                )
                chunk_duration_ms = len(chunk) / 2 / self.config.sample_rate * 1000
                voice_state = self.vad.detect(chunk, self.config.sample_rate)

                if voice_state == VoiceState.SPEECH:
                    audio_buffer.extend(chunk)
                    silence_duration_ms = 0.0
                    if speech_start is None:
                        speech_start = chunk_time
                    if self._response_is_active():
                        overlap_speech_ms += chunk_duration_ms
                        if (
                            overlap_speech_ms
                            >= self.config.interruption_confirmation_ms
                        ):
                            await self._handle_interruption()
                    else:
                        overlap_speech_ms = 0.0
                    continue

                overlap_speech_ms = 0.0
                if speech_start is None:
                    continue
                silence_duration_ms += chunk_duration_ms
                if silence_duration_ms < self.config.min_speech_duration_ms:
                    continue

                utterance = bytes(audio_buffer)
                audio_buffer.clear()
                silence_duration_ms = 0.0
                vad_ms = (chunk_time - speech_start) * 1000
                speech_start = None

                asr_start = time.perf_counter()
                transcript = await self.asr.transcribe(
                    utterance, self.config.sample_rate
                )
                asr_ms = (time.perf_counter() - asr_start) * 1000
                if transcript.strip():
                    await self._start_generation(transcript.strip(), vad_ms, asr_ms)
        finally:
            if self._generation_task is not None:
                await asyncio.gather(self._generation_task, return_exceptions=True)
            await self._output_queue.close()

    async def _start_generation(
        self, transcript: str, vad_ms: float, asr_ms: float
    ) -> None:
        if self._response_is_active():
            await self._handle_interruption()
        generation_id = uuid.uuid4().hex
        self._current_generation_id = generation_id
        self._interrupted = False
        self._conversation_history.append({"role": "user", "content": transcript})
        start_time = time.perf_counter()
        metrics = LatencyMetrics(vad_ms=vad_ms, asr_ms=asr_ms)
        self._generation_task = asyncio.create_task(
            self._produce_response(
                transcript, generation_id, start_time, metrics
            ),
            name=f"triplex-generation-{generation_id}",
        )

    async def _produce_response(
        self,
        transcript: str,
        generation_id: str,
        start_time: float,
        metrics: LatencyMetrics,
    ) -> None:
        llm_start = time.perf_counter()
        first_token = True
        first_audio = True
        full_response = ""
        synthesized_text = ""
        sequence = 0

        try:
            history = self._conversation_history[:-1]
            async for token_chunk in self.llm.generate_stream(transcript, history):
                if first_token:
                    metrics = replace(
                        metrics,
                        llm_first_token_ms=(time.perf_counter() - llm_start) * 1000,
                    )
                    first_token = False
                full_response = token_chunk.text
                if not (
                    token_chunk.is_final
                    or self._is_sentence_complete(token_chunk.text)
                ):
                    continue

                text_to_speak = token_chunk.text
                if text_to_speak.startswith(synthesized_text):
                    text_to_speak = text_to_speak[len(synthesized_text) :]
                text_to_speak = text_to_speak.strip()
                if not text_to_speak:
                    continue

                tts_start = time.perf_counter()
                async for synthesized in self._synthesize_chunks(text_to_speak):
                    if first_audio:
                        metrics = replace(
                            metrics,
                            tts_first_chunk_ms=(time.perf_counter() - tts_start) * 1000,
                        )
                        first_audio = False
                    pcm = _convert_pcm_rate(
                        synthesized.pcm,
                        synthesized.sample_rate,
                        self.config.output_sample_rate,
                    )
                    self._alignment.register_segment(
                        generation_id, synthesized.text, len(pcm) // 2
                    )
                    output_metrics = replace(
                        metrics,
                        total_ms=(time.perf_counter() - start_time) * 1000,
                    )
                    await self._output_queue.put(
                        PipelineOutput(
                            audio=pcm,
                            metrics=output_metrics,
                            generation_id=generation_id,
                            sequence=sequence,
                            text=synthesized.text,
                            sample_rate=self.config.output_sample_rate,
                        )
                    )
                    sequence += 1
                synthesized_text = token_chunk.text

            if full_response:
                self._conversation_history.append(
                    {
                        "role": "assistant",
                        "content": full_response,
                        "generation_id": generation_id,
                    }
                )
                self._trim_history()
        except asyncio.CancelledError:
            raise

    async def _synthesize_chunks(
        self, text: str
    ) -> AsyncIterator[SynthesizedAudioChunk]:
        annotated = getattr(self.tts, "synthesize_stream_chunks", None)
        if callable(annotated):
            async for chunk in annotated(text):
                yield chunk
            return

        sample_rate = int(
            getattr(
                getattr(self.tts, "config", None),
                "sample_rate",
                self.config.output_sample_rate,
            )
        )
        first = True
        async for pcm in self.tts.synthesize_stream(text):
            yield SynthesizedAudioChunk(
                pcm=pcm,
                sample_rate=sample_rate,
                text=text if first else "",
            )
            first = False

    def _response_is_active(self) -> bool:
        generation_id = self._current_generation_id
        if generation_id is None or self._interrupted:
            return False
        if self._generation_task is not None and not self._generation_task.done():
            return True
        total = self._alignment.total_samples(generation_id)
        return total > self._alignment.played_samples(generation_id)

    async def _handle_interruption(self) -> InterruptionResult | None:
        generation_id = self._current_generation_id
        if generation_id is None or self._interrupted:
            return None
        self._interrupted = True

        generation_task = self._generation_task
        if generation_task is not None and not generation_task.done():
            generation_task.cancel()

        coordinator = TearDownCoordinator(
            flush_local=self._output_queue.flush,
            abort_generation=self._abort_llm,
            flush_playout=self._playout_flush,
            deadline_ms=float(self.config.target_interruption_latency_ms),
        )
        result = await coordinator.trigger(
            generation_id, self._alignment.played_samples(generation_id)
        )
        if generation_task is not None:
            await asyncio.gather(generation_task, return_exceptions=True)

        self._alignment.acknowledge(generation_id, result.played_samples)
        spoken_text = self._alignment.spoken_text(generation_id)
        self._align_history(generation_id, spoken_text)
        if self.echo_canceller is not None:
            self.echo_canceller.clear_reference()
        self._last_interruption = result
        self._current_generation_id = None
        return result

    async def _abort_llm(self) -> None:
        abort = getattr(self.llm, "abort", None)
        if callable(abort):
            await abort()

    def _align_history(self, generation_id: str, spoken_text: str) -> None:
        for index, turn in enumerate(self._conversation_history):
            if turn.get("generation_id") == generation_id:
                if spoken_text:
                    self._conversation_history[index] = {
                        "role": "assistant",
                        "content": spoken_text,
                        "generation_id": generation_id,
                    }
                else:
                    del self._conversation_history[index]
                return
        if spoken_text:
            self._conversation_history.append(
                {
                    "role": "assistant",
                    "content": spoken_text,
                    "generation_id": generation_id,
                }
            )

    def _trim_history(self) -> None:
        max_items = self.config.max_history_turns * 2
        if len(self._conversation_history) > max_items:
            self._conversation_history = self._conversation_history[-max_items:]

    @staticmethod
    def _is_sentence_complete(text: str) -> bool:
        return bool(text and text.rstrip().endswith((".", "!", "?")))

    def reset_conversation(self) -> None:
        if self._response_is_active():
            raise RuntimeError("cannot reset conversation during active generation")
        self._conversation_history.clear()
        self._alignment = SpokenTextTracker()
        self._current_generation_id = None
        self._interrupted = False
        if self.echo_canceller is not None:
            self.echo_canceller.reset()


def _convert_pcm_rate(pcm: bytes, source_rate: int, target_rate: int) -> bytes:
    if source_rate == target_rate:
        return pcm
    audio = np.frombuffer(pcm, dtype="<i2")
    converted = resample(audio, source_rate, target_rate).astype("<i2")
    return converted.tobytes()
