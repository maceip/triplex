"""Speech synthesis (TTS) for generating agent responses.

Supports multiple backends:
- Edge-TTS (free, cloud-based)
- Local neural TTS (Piper, Coqui)
- API-based (OpenAI, ElevenLabs)
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from enum import Enum
from typing import Protocol


class VoiceStyle(Enum):
    """Voice style for synthesis."""

    NEUTRAL = "neutral"
    FRIENDLY = "friendly"
    PROFESSIONAL = "professional"


@dataclass
class TTSConfig:
    """Configuration for TTS."""

    voice_id: str = "en-US-AriaNeural"  # Default voice
    style: VoiceStyle = VoiceStyle.PROFESSIONAL
    rate: float = 1.0  # Speaking rate multiplier
    pitch: float = 1.0  # Pitch multiplier


class SpeechSynthesizer(Protocol):
    """Protocol for TTS backends."""

    async def synthesize(self, text: str, config: TTSConfig) -> bytes:
        """Synthesize speech from text.

        Args:
            text: Text to speak
            config: TTS configuration

        Returns:
            Raw PCM audio bytes (16kHz mono)
        """
        ...


class EdgeTTSSynthesizer:
    """TTS using Microsoft Edge's free online TTS service.

    Good quality, no API key needed, but requires internet.
    """

    def __init__(self) -> None:
        self._client = None

    async def _get_client(self):
        """Lazy load edge-tts."""
        if self._client is None:
            import edge_tts

            self._client = edge_tts
        return self._client

    async def synthesize(self, text: str, config: TTSConfig) -> bytes:
        """Synthesize using edge-tts."""
        edge_tts = await self._get_client()

        communicate = edge_tts.Communicate(
            text=text,
            voice=config.voice_id,
            rate=f"{int((config.rate - 1) * 100):+d}%",
        )

        # Collect audio data
        audio_data = b""
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_data += chunk["data"]

        return audio_data  # MP3 format


class MockSynthesizer:
    """Mock TTS for testing without actual synthesis."""

    async def synthesize(self, text: str, config: TTSConfig) -> bytes:
        """Return empty bytes."""
        return b""


async def synthesize_speech(
    text: str,
    config: TTSConfig | None = None,
    backend: str = "edge",
) -> bytes:
    """High-level speech synthesis function.

    Args:
        text: Text to synthesize
        config: TTS configuration
        backend: Which TTS backend to use ("edge", "mock")

    Returns:
        Audio bytes (format depends on backend)
    """
    config = config or TTSConfig()

    if backend == "edge":
        synth = EdgeTTSSynthesizer()
    elif backend == "mock":
        synth = MockSynthesizer()
    else:
        raise ValueError(f"Unknown TTS backend: {backend}")

    return await synth.synthesize(text, config)


# Latency considerations for Duplex vs Triplex:
#
# Duplex 2018:
# - Text → TTS took multiple seconds
# - Used synthetic "um" and "uh" to stall while waiting
# - Required complex timing workarounds
#
# Triplex 2026:
# - Target: sub-300ms end-to-end
# - Use streaming TTS (start playing before synthesis complete)
# - Or use end-to-end audio models (GPT-4o-audio) that skip TTS entirely
#
# For production, consider:
# - Streaming the first audio chunk while synthesizing the rest
# - Pre-caching common phrases ("Let me check", "One moment")
# - Using local TTS (Piper) for lower latency
