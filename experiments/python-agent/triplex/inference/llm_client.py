"""Voice LLM client for understanding and generating responses.

Supports multiple backends:
- OpenAI GPT-4o-audio (cloud)
- Google Gemini Live (cloud)
- Local models (via Ollama or similar)

Key difference from Duplex 2018:
- Zero-shot handling of unexpected responses
- No rigid intent classification
- Natural conversational timing
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Protocol

import httpx


class LLMBackend(Enum):
    """Available LLM backends."""

    OPENAI_GPT4O_AUDIO = "openai_gpt4o_audio"
    GEMINI_LIVE = "gemini_live"
    LOCAL_OLLAMA = "local_ollama"
    MOCK = "mock"


@dataclass
class LLMConfig:
    """Configuration for LLM."""

    backend: LLMBackend = LLMBackend.MOCK
    api_key: str | None = None
    model: str = "gpt-4o-audio-preview"
    temperature: float = 0.7
    max_tokens: int = 500
    system_prompt: str = "You are a helpful phone assistant."


@dataclass
class VoiceLLMResponse:
    """Response from the voice LLM."""

    text: str
    audio: bytes | None = None
    reasoning: str | None = None
    action: str | None = None  # "continue", "confirm", "escalate", "hang_up"
    extracted_info: dict[str, Any] = field(default_factory=dict)


class VoiceLLMClient(Protocol):
    """Protocol for voice LLM clients."""

    async def understand(
        self,
        audio: bytes | None = None,
        text: str | None = None,
        context: dict[str, Any] | None = None,
    ) -> VoiceLLMResponse:
        """Understand audio or text input."""
        ...

    async def generate_response(
        self,
        context: dict[str, Any],
        task: str,
    ) -> VoiceLLMResponse:
        """Generate a response given context."""
        ...


class OpenAIGPT4oAudioClient:
    """Client for OpenAI's GPT-4o audio model.

    This is the recommended backend for Triplex:
    - Native audio understanding (no ASR bottleneck)
    - Natural conversational rhythm
    - Low latency (streaming)
    """

    def __init__(self, config: LLMConfig):
        self.config = config
        self._client = httpx.AsyncClient()

    async def understand(
        self,
        audio: bytes | None = None,
        text: str | None = None,
        context: dict[str, Any] | None = None,
    ) -> VoiceLLMResponse:
        """Understand audio input using GPT-4o audio."""
        if not self.config.api_key:
            raise ValueError("OpenAI API key required")

        # Build the request
        messages = []

        # System prompt
        messages.append({
            "role": "system",
            "content": self.config.system_prompt,
        })

        # Context from previous turns
        if context and "transcript" in context:
            for turn in context["transcript"]:
                messages.append({
                    "role": "user" if turn["speaker"] == "recipient" else "assistant",
                    "content": turn["text"],
                })

        # Current input
        if text:
            messages.append({"role": "user", "content": text})
        elif audio:
            # For audio input, use the audio content type
            import base64

            audio_base64 = base64.b64encode(audio).decode()
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": audio_base64,
                            "format": "pcm16",
                        },
                    }
                ],
            })

        # Make the API call
        response = await self._client.post(
            "https://api.openai.com/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {self.config.api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": self.config.model,
                "messages": messages,
                "temperature": self.config.temperature,
                "max_tokens": self.config.max_tokens,
            },
            timeout=30.0,
        )

        response.raise_for_status()
        data = response.json()

        return VoiceLLMResponse(
            text=data["choices"][0]["message"]["content"],
        )

    async def generate_response(
        self,
        context: dict[str, Any],
        task: str,
    ) -> VoiceLLMResponse:
        """Generate a response."""
        # Similar to understand but focused on generation
        return await self.understand(text=task, context=context)


class MockLLMClient:
    """Mock LLM for testing."""

    async def understand(
        self,
        audio: bytes | None = None,
        text: str | None = None,
        context: dict[str, Any] | None = None,
    ) -> VoiceLLMResponse:
        """Return a mock response."""
        return VoiceLLMResponse(
            text="I understand. Let me help you with that.",
            action="continue",
        )

    async def generate_response(
        self,
        context: dict[str, Any],
        task: str,
    ) -> VoiceLLMResponse:
        """Return a mock response."""
        return VoiceLLMResponse(
            text="I can help with that. One moment please.",
            action="continue",
        )


def create_llm_client(config: LLMConfig) -> VoiceLLMClient:
    """Factory for LLM clients."""
    if config.backend == LLMBackend.OPENAI_GPT4O_AUDIO:
        return OpenAIGPT4oAudioClient(config)
    elif config.backend == LLMBackend.MOCK:
        return MockLLMClient()
    else:
        raise ValueError(f"Unsupported backend: {config.backend}")


# Example prompt for handling unexpected responses:
UNEXPECTED_RESPONSE_PROMPT = """
The recipient gave an unexpected response. Help me handle this gracefully.

Context:
- Task: {task}
- Expected: {expected}
- Got: {actual}

Consider:
1. Is this a compromise we can accept?
2. Do we need to ask the user?
3. Should we escalate to human intervention?

Respond with:
- understanding: what they said
- acceptable: true/false
- user_input_needed: true/false
- response: what to say back
"""
