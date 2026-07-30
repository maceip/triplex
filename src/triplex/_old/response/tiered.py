"""Tiered response system for low-latency conversation flow.

Based on Google Duplex's insight: humans expect different response times
for different conversational situations:

- "Hello?" → expects < 100ms response or it feels wrong
- Simple question → ~300ms is acceptable
- Complex question → 1-4s is normal, feels like "thinking"

This module provides multiple response paths with different latency targets.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable

import numpy as np


class Urgency(Enum):
    """How quickly a response is needed."""

    IMMEDIATE = "immediate"  # < 100ms - "hello?", "yeah?", "are you there?"
    FAST = "fast"           # ~300ms - simple acknowledgments, short replies
    NORMAL = "normal"       # 1-2s - standard conversational responses
    THOUGHTFUL = "thoughtful"  # 2-4s - complex questions, "let me think"


@dataclass
class Response:
    """A response from one of the tiered systems."""

    text: str
    audio: np.ndarray | None = None
    latency_ms: float = 0.0
    tier: Urgency = Urgency.NORMAL
    is_placeholder: bool = False  # True if this is a stalling response


@dataclass
class TieredConfig:
    """Configuration for tiered response system."""

    immediate_threshold_ms: float = 100.0
    fast_threshold_ms: float = 300.0
    normal_threshold_ms: float = 1500.0

    # Pre-cached immediate responses (spoken in < 100ms)
    immediate_responses: tuple[str, ...] = (
        "hello",
        "hi",
        "yeah",
        "yes",
        "mm-hmm",
        "okay",
        "sure",
        "right",
    )

    # Stalling responses when we need more time
    stalling_responses: tuple[str, ...] = (
        "let me check",
        "one moment",
        "hmm, let me think",
        "sure, one second",
    )


class ImmediateResponseDetector:
    """Detects when an immediate response is required.

    Signals that need < 100ms response:
    - Greetings: "hello?", "hi?", "hey?"
    - Checking presence: "are you there?", "you still there?"
    - Simple confirmations: "okay?", "right?"
    """

    GREETINGS = {"hello", "hi", "hey", "good morning", "good afternoon"}
    PRESENCE_CHECKS = {"are you there", "you there", "still there", "can you hear"}
    SIMPLE_PROMPTS = {"okay", "right", "yeah", "yes", "sure"}

    def detect(self, text: str) -> Urgency:
        """Analyze transcribed text to determine response urgency.

        Args:
            text: Transcribed speech (may be partial/streaming)

        Returns:
            Urgency level indicating how quickly to respond
        """
        text_lower = text.lower().strip()

        # Check for greetings
        for greeting in self.GREETINGS:
            if text_lower.startswith(greeting) or text_lower == greeting:
                return Urgency.IMMEDIATE

        # Check for presence checks
        for check in self.PRESENCE_CHECKS:
            if check in text_lower:
                return Urgency.IMMEDIATE

        # Short simple prompts after we've said something
        # (These are acknowledging what we said, respond quickly)
        if text_lower in self.SIMPLE_PROMPTS or len(text_lower.split()) <= 2:
            return Urgency.FAST

        # Default to normal
        return Urgency.NORMAL


class TieredResponder:
    """Manages multiple response paths with different latency targets.

    Architecture:

    ┌──────────────────────────────────────────────────────────────┐
    │                      INCOMING AUDIO                           │
    └──────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  ImmediateResponseDetector → Urgency (IMMEDIATE/FAST/NORMAL) │
    └──────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │ IMMEDIATE    │  │ FAST         │  │ NORMAL       │
    │ < 100ms      │  │ ~300ms       │  │ 1-4s         │
    │              │  │              │  │              │
    │ Pre-cached   │  │ Small model  │  │ Full LLM     │
    │ audio files  │  │ or cached    │  │ pipeline     │
    └──────────────┘  └──────────────┘  └──────────────┘
              │               │               │
              └───────────────┴───────────────┘
                              │
                              ▼
                       RESPONSE OUT
    """

    def __init__(
        self,
        llm_client: Any = None,
        tts_func: Callable[[str], np.ndarray] | None = None,
        config: TieredConfig | None = None,
    ):
        self.llm = llm_client
        self.tts = tts_func
        self.config = config or TieredConfig()
        self.detector = ImmediateResponseDetector()

        # Pre-synthesize immediate responses
        self._immediate_audio_cache: dict[str, np.ndarray] = {}
        self._cache_ready = False

    async def preload_cache(self) -> None:
        """Pre-synthesize immediate responses for < 100ms playback.

        This MUST be called at startup to have instant responses ready.
        """
        if self.tts is None:
            return

        for text in self.config.immediate_responses:
            if text not in self._immediate_audio_cache:
                self._immediate_audio_cache[text] = await self._synthesize(text)

        self._cache_ready = True

    async def respond(
        self,
        audio: np.ndarray,
        context: dict[str, Any] | None = None,
    ) -> Response:
        """Generate a response with appropriate latency tier.

        This orchestrates the tiered response system:
        1. Transcribe (fast or streaming)
        2. Detect urgency
        3. Route to appropriate response path
        4. Return response with timing
        """
        start = time.perf_counter()

        # Transcribe (this would normally be streaming)
        # For now, assume we get text from somewhere
        text = await self._transcribe(audio)

        # Detect urgency
        urgency = self.detector.detect(text)

        # Route to appropriate tier
        if urgency == Urgency.IMMEDIATE:
            response = await self._immediate_response(text)
        elif urgency == Urgency.FAST:
            response = await self._fast_response(text, context)
        else:
            response = await self._normal_response(text, context)

        response.latency_ms = (time.perf_counter() - start) * 1000
        response.tier = urgency

        return response

    async def _immediate_response(self, trigger_text: str) -> Response:
        """Return a pre-cached immediate response (< 100ms target).

        These are simple acknowledgments that signal presence:
        - "hello" → "hi"
        - "are you there?" → "yes, I'm here"
        """
        # Select appropriate response
        text_lower = trigger_text.lower()

        if text_lower.startswith("hello") or text_lower == "hello":
            response_text = "hello"
        elif text_lower.startswith("hi") or text_lower == "hi":
            response_text = "hi"
        elif "there" in text_lower:
            response_text = "yes, I'm here"
        else:
            response_text = "yes"

        # Get pre-cached audio
        audio = self._immediate_audio_cache.get(response_text)

        return Response(
            text=response_text,
            audio=audio,
            tier=Urgency.IMMEDIATE,
        )

    async def _fast_response(
        self,
        text: str,
        context: dict[str, Any] | None,
    ) -> Response:
        """Fast response path (~300ms target).

        Uses:
        - Small/fast model
        - Cached common phrases
        - Simple intent matching
        """
        # TODO: Implement fast path with small model
        # For now, return a simple acknowledgment

        response_text = "I understand."

        audio = None
        if self.tts:
            audio = await self._synthesize(response_text)

        return Response(
            text=response_text,
            audio=audio,
            tier=Urgency.FAST,
        )

    async def _normal_response(
        self,
        text: str,
        context: dict[str, Any] | None,
    ) -> Response:
        """Normal response path (1-4s).

        Uses full LLM pipeline for complex responses.
        """
        if self.llm is None:
            return Response(text="I'm not sure how to respond.", tier=Urgency.NORMAL)

        # Full LLM response
        llm_response = await self.llm.respond(text=text, context=context)

        audio = None
        if self.tts:
            audio = await self._synthesize(llm_response.text)

        return Response(
            text=llm_response.text,
            audio=audio,
            tier=Urgency.NORMAL,
        )

    async def _transcribe(self, audio: np.ndarray) -> str:
        """Transcribe audio to text.

        This should use streaming ASR for latency - we want partial
        transcripts to start processing before the user finishes speaking.
        """
        # TODO: Connect to Whisper or other ASR
        # For now, placeholder
        return "[transcribed text]"

    async def _synthesize(self, text: str) -> np.ndarray:
        """Synthesize speech from text."""
        if self.tts is None:
            return np.array([])

        if asyncio.iscoroutinefunction(self.tts):
            return await self.tts(text)
        else:
            return self.tts(text)

    def get_stalling_response(self) -> Response:
        """Get a stalling response when we need more time.

        Duplex used "um" and "uh" - we use more natural phrases.
        This is called when the normal path is taking too long.
        """
        import random

        text = random.choice(self.config.stalling_responses)
        return Response(
            text=text,
            is_placeholder=True,
            tier=Urgency.FAST,
        )
