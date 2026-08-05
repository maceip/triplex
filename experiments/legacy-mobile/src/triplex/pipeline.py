"""Voice agent pipeline - connects all layers.

Audio In → VAD → (if speech) → LLM → TTS → Audio Out

All layers run concurrently to achieve sub-300ms latency.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass

from .asr.whisper_engine import ASRConfig, MockASR, WhisperASR
from .audio.vad import VoiceActivityDetector, VoiceState
from .reasoning.vllm_engine import MockReasoningEngine, VLLMEngine
from .synthesis.kokoro_engine import KokoroEngine


@dataclass
class PipelineConfig:
    """Configuration for the voice pipeline."""

    # Latency targets (ms)
    target_total_latency_ms: int = 300
    target_reflex_latency_ms: int = 100
    target_synthesis_latency_ms: int = 150

    # Audio settings
    sample_rate: int = 16000
    chunk_duration_ms: int = 20  # 20ms audio chunks

    # VAD settings
    vad_backend: str = "silero"
    speech_threshold: float = 0.5
    min_speech_duration_ms: int = 250

    # Interruption settings
    interrupt_threshold_ms: int = 50  # React to interruption within 50ms

    # Reasoning
    use_mock_llm: bool = False

    # ASR
    use_mock_asr: bool = True  # Default to mock, Whisper needs GPU
    asr_model: str = "small.en"  # tiny.en, base.en, small.en

    # Conversation
    max_history_turns: int = 10


@dataclass
class LatencyMetrics:
    """Latency tracking for each stage."""

    vad_ms: float = 0.0
    llm_first_token_ms: float = 0.0
    tts_first_chunk_ms: float = 0.0
    total_ms: float = 0.0

    def __str__(self) -> str:
        return (
            f"VAD: {self.vad_ms:.1f}ms | "
            f"LLM: {self.llm_first_token_ms:.1f}ms | "
            f"TTS: {self.tts_first_chunk_ms:.1f}ms | "
            f"Total: {self.total_ms:.1f}ms"
        )


class VoicePipeline:
    """End-to-end voice agent pipeline.

    Architecture:
    ```
    Audio In ──► VAD ──► Transit Buffer ──► LLM ──► TTS ──► Audio Out
                    │                      │       │
                    │                      │       └── Streaming chunks
                    │                      └── Streaming tokens
                    └── Detects end of speech
    ```

    Key features:
    - Streaming throughout (no batch processing)
    - Interruption detection and handling
    - Latency tracking at each stage
    """

    def __init__(self, config: PipelineConfig | None = None):
        self.config = config or PipelineConfig()

        # Initialize components
        self.vad = VoiceActivityDetector(
            backend=self.config.vad_backend,
        )
        self.tts = KokoroEngine()
        self.llm = VLLMEngine() if not self.config.use_mock_llm else MockReasoningEngine()
        self.asr = (
            WhisperASR(ASRConfig(model_size=self.config.asr_model))
            if not self.config.use_mock_asr
            else MockASR()
        )

        # State
        self._conversation_history: list[dict] = []
        self._is_speaking = False
        self._interrupted = False
        self._current_generation_task: asyncio.Task | None = None

        # Metrics
        self._latency = LatencyMetrics()

    def initialize(self) -> None:
        """Initialize all components."""
        if not self.config.use_mock_llm:
            self.llm.initialize()
        self.tts.initialize()

    async def process_audio_stream(
        self,
        audio_stream: AsyncIterator[bytes],
    ) -> AsyncIterator[tuple[bytes, LatencyMetrics]]:
        """Process incoming audio and yield responses.

        Args:
            audio_stream: Async iterator of audio chunks (PCM, 16kHz)

        Yields:
            (audio_response_chunk, latency_metrics) tuples
        """
        audio_buffer = bytearray()
        last_voice_state = VoiceState.SILENCE
        silence_duration_ms = 0.0
        speech_start: float | None = None

        pipeline_start_time: float | None = None

        async for chunk in audio_stream:
            chunk_time = time.perf_counter()

            # Detect voice activity
            voice_state = self.vad.detect(chunk, self.config.sample_rate)
            if voice_state == VoiceState.SPEECH:
                audio_buffer.extend(chunk)

                if last_voice_state == VoiceState.SILENCE:
                    # Speech started
                    speech_start = chunk_time
                    silence_duration_ms = 0.0

                # Check for interruption (user speaking while we are)
                if self._is_speaking:
                    await self._handle_interruption()

            else:  # SILENCE
                if speech_start is not None:
                    samples = len(chunk) / 2
                    silence_duration_ms += samples / self.config.sample_rate * 1000

                # Check if silence duration exceeds threshold
                if (
                    speech_start is not None
                    and silence_duration_ms >= self.config.min_speech_duration_ms
                ):
                    # End of utterance detected - process it
                    pipeline_start_time = time.perf_counter()
                    self._latency.vad_ms = (chunk_time - speech_start) * 1000

                    # Transcribe (mock for now - integrate Whisper later)
                    transcript = await self._transcribe(bytes(audio_buffer))

                    # Clear buffer
                    audio_buffer.clear()
                    silence_duration_ms = 0.0
                    speech_start = None

                    # Generate response
                    async for audio_out, metrics in self._generate_response_stream(
                        transcript, pipeline_start_time
                    ):
                        yield audio_out, metrics

            last_voice_state = voice_state

    async def _transcribe(self, audio: bytes) -> str:
        """Transcribe audio to text using ASR."""
        return await self.asr.transcribe(audio, self.config.sample_rate)

    async def _generate_response_stream(
        self,
        transcript: str,
        start_time: float,
    ) -> AsyncIterator[tuple[bytes, LatencyMetrics]]:
        """Generate response and yield audio chunks.

        Streams through: LLM → TTS → Audio
        """
        self._is_speaking = True

        # Add to conversation history
        self._conversation_history.append({"role": "user", "content": transcript})

        # Track LLM first token latency
        llm_start = time.perf_counter()
        first_token = True

        # Full response text for history
        full_response = ""
        spoken_text = ""
        first_audio_chunk = True

        try:
            # Stream from LLM
            async for token_chunk in self.llm.generate_stream(
                transcript, self._conversation_history
            ):
                if self._interrupted:
                    break

                # Track first token latency
                if first_token:
                    self._latency.llm_first_token_ms = (time.perf_counter() - llm_start) * 1000
                    first_token = False

                full_response = token_chunk.text

                # Stream to TTS
                # For now, only synthesize when we have a complete sentence
                # TODO: Implement sentence-level streaming
                if token_chunk.is_final or self._is_sentence_complete(token_chunk.text):
                    text_to_speak = token_chunk.text
                    if text_to_speak.startswith(spoken_text):
                        text_to_speak = text_to_speak[len(spoken_text) :]
                    text_to_speak = text_to_speak.strip()

                    if not text_to_speak:
                        continue

                    tts_start = time.perf_counter()

                    async for audio_chunk in self.tts.synthesize_stream(text_to_speak):
                        if self._interrupted:
                            break

                        if first_audio_chunk:
                            self._latency.tts_first_chunk_ms = (
                                time.perf_counter() - tts_start
                            ) * 1000
                            first_audio_chunk = False

                        self._latency.total_ms = (time.perf_counter() - start_time) * 1000

                        yield audio_chunk, self._latency

                    spoken_text = token_chunk.text

            # Add assistant response to history
            if full_response:
                self._conversation_history.append({"role": "assistant", "content": full_response})

                # Trim history if too long
                if len(self._conversation_history) > self.config.max_history_turns * 2:
                    self._conversation_history = self._conversation_history[
                        -self.config.max_history_turns * 2 :
                    ]

        finally:
            self._is_speaking = False
            self._interrupted = False

    async def _handle_interruption(self) -> None:
        """Handle user interruption.

        Tear-down cascade:
        1. Stop TTS synthesis
        2. Stop LLM generation
        3. Flush audio buffer
        """
        self._interrupted = True

        # Cancel any running generation
        if self._current_generation_task:
            self._current_generation_task.cancel()

        # The audio output will naturally stop as _interrupted is checked in the stream

    def _is_sentence_complete(self, text: str) -> bool:
        """Check if text ends a sentence."""
        if not text:
            return False

        # Check for sentence-ending punctuation
        sentence_enders = [".", "!", "?"]
        return any(text.rstrip().endswith(ender) for ender in sentence_enders)

    def reset_conversation(self) -> None:
        """Clear conversation state."""
        self._conversation_history.clear()
        self._interrupted = False
        self._is_speaking = False


async def run_pipeline_demo():
    """Demo the pipeline with mock audio."""

    config = PipelineConfig(use_mock_llm=True)
    pipeline = VoicePipeline(config)
    pipeline.initialize()

    # Simulate audio stream
    async def mock_audio_stream():
        """Generate mock audio chunks."""
        # Simulate 2 seconds of "speech" followed by silence
        for _ in range(100):  # 100 chunks of 20ms = 2 seconds
            yield b"\x00" * 640  # 20ms of 16kHz 16-bit audio
        # Then silence to trigger end-of-speech
        for _ in range(50):
            yield b"\x00" * 640

    print("Running pipeline demo...")
    print("-" * 50)

    async for audio_chunk, metrics in pipeline.process_audio_stream(mock_audio_stream()):
        print(f"Output chunk: {len(audio_chunk)} bytes")
        print(f"Latency: {metrics}")
        print("-" * 50)

    print("\nDemo complete!")


if __name__ == "__main__":
    asyncio.run(run_pipeline_demo())
