"""Kokoro-82M TTS engine.

82M parameter decoder-only model:
- StyleTTS 2 architecture
- 24kHz output
- Sub-100ms TTFA (Time To First Audio)
- Decoupled from voice cloning (Phase 1)

Install: pip install kokoro>=0.9.2 soundfile
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import numpy as np

try:
    from kokoro import KPipeline

    HAS_KOKORO = True
except ImportError:
    HAS_KOKORO = False


@dataclass
class KokoroConfig:
    """Configuration for Kokoro TTS."""

    model_name: str = "hexgrad/Kokoro-82M"
    default_voice: str = "af_heart"  # American female, warm
    lang_code: str = "a"  # American English
    sample_rate: int = 24000
    speed: float = 1.0


# Recommended branded voices
VOICES = {
    # American English
    "af_heart": "Warm, friendly female",
    "af_sky": "Professional female",
    "am_adam": "Deep male",
    "am_michael": "Clear male",
    # British English
    "bf_emma": "British female",
    "bm_george": "British male",
    # Japanese, Chinese, Korean, etc. available
}


class KokoroEngine:
    """Kokoro-82M TTS for branded voice synthesis.

    Phases:
    - Phase 1: Use pre-trained voices (af_heart, am_adam, etc.)
    - Phase 1.5: Fine-tune custom voice embedding for brand
    - Phase 2: Route to zero-shot engine for customer voices

    Latency profile:
    - First audio chunk: ~50-100ms
    - Streaming: Yes (generator yields chunks)
    """

    def __init__(self, config: KokoroConfig | None = None):
        if not HAS_KOKORO:
            raise ImportError(
                "Kokoro required: pip install kokoro>=0.9.2 soundfile\n"
                "On Linux, also need: apt-get install espeak-ng"
            )

        self.config = config or KokoroConfig()
        self._pipeline: KPipeline | None = None
        self._initialized = False

    def initialize(self) -> None:
        """Load model (lazy)."""
        if self._initialized:
            return

        print(f"Loading Kokoro-82M...")
        self._pipeline = KPipeline(lang_code=self.config.lang_code)
        self._initialized = True
        print("Kokoro loaded")

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
    ) -> tuple[np.ndarray, int]:
        """Synthesize speech from text.

        Args:
            text: Text to speak
            voice: Voice ID (default: configured voice)

        Returns:
            Tuple of (audio_array, sample_rate)
        """
        self.initialize()

        voice = voice or self.config.default_voice

        # Kokoro returns a generator that yields (graphemes, phonemes, audio)
        generator = self._pipeline(text, voice=voice, speed=self.config.speed)

        # Collect all audio chunks
        audio_chunks = []
        for _, _, audio in generator:
            if isinstance(audio, np.ndarray):
                audio_chunks.append(audio)

        if audio_chunks:
            audio = np.concatenate(audio_chunks)
        else:
            audio = np.array([], dtype=np.float32)

        return audio, self.config.sample_rate

    def synthesize_streaming(
        self,
        text: str,
        voice: str | None = None,
    ) -> Iterator[tuple[np.ndarray, float]]:
        """Stream audio chunks as they're generated.

        Yields:
            Tuple of (audio_chunk, duration_seconds)
        """
        self.initialize()

        voice = voice or self.config.default_voice
        generator = self._pipeline(text, voice=voice, speed=self.config.speed)

        for _, _, audio in generator:
            if isinstance(audio, np.ndarray):
                duration = len(audio) / self.config.sample_rate
                yield audio, duration

    async def synthesize_async(
        self,
        text: str,
        voice: str | None = None,
    ) -> tuple[np.ndarray, int]:
        """Async wrapper."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None,
            lambda: self.synthesize(text, voice),
        )


class VoiceProfile:
    """Voice profile for a specific use case.

    Phase 1: Pre-trained voice (e.g., af_heart)
    Phase 1.5: Fine-tuned embedding (.pt file)
    Phase 2: Zero-shot reference audio
    """

    def __init__(
        self,
        name: str,
        voice_id: str,
        description: str = "",
        embedding_path: Path | None = None,
        reference_audio: Path | None = None,
    ):
        self.name = name
        self.voice_id = voice_id
        self.description = description
        self.embedding_path = embedding_path
        self.reference_audio = reference_audio

        # Phase 1: Just voice_id
        # Phase 1.5: Custom embedding
        # Phase 2: Reference audio for cloning

    @classmethod
    def branded(cls, name: str, voice_id: str) -> "VoiceProfile":
        """Create a branded voice profile using pre-trained voice."""
        return cls(name=name, voice_id=voice_id)

    @classmethod
    def fine_tuned(cls, name: str, embedding_path: Path) -> "VoiceProfile":
        """Create a fine-tuned voice profile."""
        return cls(name=name, voice_id="custom", embedding_path=embedding_path)


# Pre-configured branded voices for common business types
BRANDED_VOICES = {
    "professional_assistant": VoiceProfile.branded(
        name="Professional Assistant",
        voice_id="af_sky",
        description="Clear, professional female voice",
    ),
    "friendly_receptionist": VoiceProfile.branded(
        name="Friendly Receptionist",
        voice_id="af_heart",
        description="Warm, welcoming female voice",
    ),
    "authoritative_agent": VoiceProfile.branded(
        name="Authoritative Agent",
        voice_id="am_adam",
        description="Deep, confident male voice",
    ),
}
