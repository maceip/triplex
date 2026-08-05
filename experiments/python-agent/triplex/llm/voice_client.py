"""Voice LLM client.

Supports:
- OpenAI GPT-4o-audio (cloud, best quality)
- Google Gemini Live (cloud, streaming)
- Local Whisper + LLM (offline, but slower)

The voice LLM handles the actual conversation - understanding
what the user said and generating natural responses.
"""

from __future__ import annotations

import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

import numpy as np


@dataclass
class VoiceResponse:
    """Response from the voice LLM."""

    text: str
    audio: np.ndarray | None = None  # TTS output if available
    thinking: str | None = None  # Reasoning trace if available
    action: str | None = None  # "continue", "hang_up", etc.


class VoiceLLM(ABC):
    """Abstract base for voice LLMs."""

    @abstractmethod
    async def transcribe(self, audio: np.ndarray | bytes) -> str:
        """Transcribe audio to text."""
        pass

    @abstractmethod
    async def respond(
        self,
        text: str | None = None,
        audio: np.ndarray | bytes | None = None,
        context: list[dict[str, Any]] | None = None,
    ) -> VoiceResponse:
        """Generate a response to text or audio input."""
        pass


class MockVoiceLLM(VoiceLLM):
    """Mock LLM for testing without API calls."""

    async def transcribe(self, audio: np.ndarray | bytes) -> str:
        return "[transcribed text]"

    async def respond(
        self,
        text: str | None = None,
        audio: np.ndarray | bytes | None = None,
        context: list[dict[str, Any]] | None = None,
    ) -> VoiceResponse:
        return VoiceResponse(text="I understand. How can I help?")


class OpenAIVoiceLLM(VoiceLLM):
    """GPT-4o-audio client.

    Native audio input/output, good latency, streaming support.
    Requires OPENAI_API_KEY environment variable.
    """

    def __init__(self, api_key: str | None = None, model: str = "gpt-4o-audio-preview"):
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY")
        if not self.api_key:
            raise ValueError("OpenAI API key required. Set OPENAI_API_KEY env var.")

        self.model = model
        self._client = None

    async def _get_client(self):
        if self._client is None:
            import openai

            self._client = openai.AsyncOpenAI(api_key=self.api_key)
        return self._client

    async def transcribe(self, audio: np.ndarray | bytes) -> str:
        """Transcribe using Whisper API."""
        client = await self._get_client()

        # Convert audio to bytes if needed
        if isinstance(audio, np.ndarray):
            audio = self._numpy_to_wav(audio)

        # Use Whisper for transcription
        import io

        audio_file = io.BytesIO(audio)
        audio_file.name = "audio.wav"

        response = await client.audio.transcriptions.create(
            model="whisper-1",
            file=audio_file,
        )

        return response.text

    async def respond(
        self,
        text: str | None = None,
        audio: np.ndarray | bytes | None = None,
        context: list[dict[str, Any]] | None = None,
    ) -> VoiceResponse:
        """Generate response using GPT-4o-audio."""
        client = await self._get_client()

        messages = []

        # Add context/history
        if context:
            for turn in context:
                messages.append(turn)

        # Add current input
        if text:
            messages.append({"role": "user", "content": text})
        elif audio:
            # For audio input, use the audio content type
            import base64

            if isinstance(audio, np.ndarray):
                audio = self._numpy_to_wav(audio)

            audio_base64 = base64.b64encode(audio).decode()
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": audio_base64,
                            "format": "wav",
                        },
                    }
                ],
            })

        response = await client.chat.completions.create(
            model=self.model,
            messages=messages,
        )

        return VoiceResponse(text=response.choices[0].message.content or "")

    def _numpy_to_wav(self, audio: np.ndarray, sample_rate: int = 16000) -> bytes:
        """Convert numpy array to WAV bytes."""
        import io
        import wave

        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(sample_rate)
            # Convert float to int16
            audio_int = (audio * 32768).astype(np.int16)
            wf.writeframes(audio_int.tobytes())

        return buffer.getvalue()


class GeminiVoiceLLM(VoiceLLM):
    """Google Gemini Live API client.

    Native audio streaming, good for real-time conversations.
    Requires GOOGLE_API_KEY environment variable.
    """

    def __init__(self, api_key: str | None = None, model: str = "gemini-2.0-flash-exp"):
        self.api_key = api_key or os.environ.get("GOOGLE_API_KEY")
        if not self.api_key:
            raise ValueError("Google API key required. Set GOOGLE_API_KEY env var.")

        self.model = model

    async def transcribe(self, audio: np.ndarray | bytes) -> str:
        """Transcribe using Gemini."""
        # Would use google-generativeai package
        raise NotImplementedError("Gemini transcription not yet implemented")

    async def respond(
        self,
        text: str | None = None,
        audio: np.ndarray | bytes | None = None,
        context: list[dict[str, Any]] | None = None,
    ) -> VoiceResponse:
        """Generate response using Gemini."""
        raise NotImplementedError("Gemini response not yet implemented")


def create_voice_llm(
    backend: str = "mock",
    **kwargs,
) -> VoiceLLM:
    """Factory for voice LLM clients.

    Args:
        backend: One of "mock", "openai", "gemini"
        **kwargs: Passed to the LLM constructor

    Returns:
        VoiceLLM instance
    """
    if backend == "mock":
        return MockVoiceLLM()
    elif backend == "openai":
        return OpenAIVoiceLLM(**kwargs)
    elif backend == "gemini":
        return GeminiVoiceLLM(**kwargs)
    else:
        raise ValueError(f"Unknown backend: {backend}")
