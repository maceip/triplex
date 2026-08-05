# SUPERSEDED-BY: RUNTIME_INVARIANTS.md §7.5
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Tier 1 Reflex Layer - Fast response decision making.

Handles conversational physics:
1. Backchanneling (ignore "uh-huh" while AI talks)
2. Interruption detection (barge-in = tear-down cascade)
3. Filler injection ("hmm" to buy time)

Target: < 100ms decision latency.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable

import numpy as np

from ..audio.vad import VoiceActivityDetector, VoiceState


class ReflexAction(Enum):
    """Actions the reflex layer can trigger."""

    IGNORE = "ignore"  # Backchannel, don't respond
    RESPOND = "respond"  # Full pipeline needed
    INTERRUPT = "interrupt"  # Tear-down cascade
    FILLER = "filler"  # Inject "hmm" to stall


@dataclass
class ReflexContext:
    """Context for reflex decisions."""

    is_ai_speaking: bool = False
    last_user_speech_time: float = 0.0
    last_ai_speech_time: float = 0.0
    consecutive_silence_ms: float = 0.0
    speech_duration_ms: float = 0.0


@dataclass
class ReflexResult:
    """Result of reflex layer processing."""

    action: ReflexAction
    confidence: float
    latency_ms: float
    speech_detected: bool
    context: ReflexContext


class ReflexLayer:
    """Tier 1 decision maker for conversational physics.

    Flow:
    1. VAD processes 20ms chunks
    2. If speech detected, check if AI is speaking
    3. If AI speaking + user speech = potential interrupt
    4. Quick intent check (backchannel vs real speech)
    5. Route to appropriate action

    Latency target: < 100ms
    """

    def __init__(
        self,
        vad_backend: str = "energy",
        on_interrupt: Callable[[], None] | None = None,
        on_filler: Callable[[], None] | None = None,
        speech_threshold_ms: float = 300.0,  # Min speech to be "real"
        silence_threshold_ms: float = 500.0,  # Silence = turn end
    ):
        self.vad = VoiceActivityDetector(backend=vad_backend)
        self.on_interrupt = on_interrupt
        self.on_filler = on_filler

        self.speech_threshold_ms = speech_threshold_ms
        self.silence_threshold_ms = silence_threshold_ms

        self._context = ReflexContext()
        self._speech_start_time: float | None = None
        self._last_chunk_time: float | None = None

    def process_chunk(self, audio: np.ndarray) -> ReflexResult:
        """Process a 20ms audio chunk.

        Args:
            audio: Audio chunk (any sample rate, will use VAD appropriately)

        Returns:
            ReflexResult with action recommendation
        """
        start = time.perf_counter()

        # Run VAD
        voice_state = self.vad.detect(audio)
        now = time.time()

        # Update context
        if voice_state == VoiceState.SPEECH:
            if self._speech_start_time is None:
                self._speech_start_time = now
            self._context.last_user_speech_time = now
            self._context.speech_duration_ms = (
                (now - self._speech_start_time) * 1000
                if self._speech_start_time
                else 0
            )
            self._context.consecutive_silence_ms = 0
        else:
            # Silence
            if self._last_chunk_time:
                silence_ms = (now - self._last_chunk_time) * 1000
                self._context.consecutive_silence_ms += silence_ms

            # Check for turn end
            if self._context.consecutive_silence_ms >= self.silence_threshold_ms:
                self._speech_start_time = None
                self._context.speech_duration_ms = 0

        self._last_chunk_time = now

        # Decision logic
        action = self._decide_action(voice_state)
        latency = (time.perf_counter() - start) * 1000

        return ReflexResult(
            action=action,
            confidence=0.8,  # TODO: Actual confidence from VAD/intent
            latency_ms=latency,
            speech_detected=voice_state == VoiceState.SPEECH,
            context=self._context,
        )

    def _decide_action(self, voice_state: VoiceState) -> ReflexAction:
        """Decide what action to take based on context."""

        if voice_state == VoiceState.SILENCE:
            # Check if we should inject filler while waiting
            if (
                self._context.is_ai_speaking
                and self._context.consecutive_silence_ms > 400
            ):
                return ReflexAction.FILLER
            return ReflexAction.IGNORE

        # User is speaking
        if self._context.is_ai_speaking:
            # Potential interrupt!
            # Check if real speech or backchannel
            if self._context.speech_duration_ms < 200:
                # Very short = likely backchannel ("uh-huh")
                return ReflexAction.IGNORE

            # Real interruption
            return ReflexAction.INTERRUPT

        # User speaking while AI silent = their turn
        if self._context.speech_duration_ms >= self.speech_threshold_ms:
            return ReflexAction.RESPOND

        # Wait for more speech
        return ReflexAction.IGNORE

    def set_ai_speaking(self, speaking: bool) -> None:
        """Update AI speaking state."""
        self._context.is_ai_speaking = speaking
        if speaking:
            self._context.last_ai_speech_time = time.time()

    def reset(self) -> None:
        """Reset state for new conversation."""
        self._context = ReflexContext()
        self._speech_start_time = None
        self._last_chunk_time = None


class InterruptHandler:
    """Handles the tear-down cascade when user interrupts.

    Steps (< 50ms total):
    1. Flush output buffer (stop audio immediately)
    2. Kill LLM generation
    3. Truncate context to last spoken word
    """

    def __init__(
        self,
        audio_buffer: Any = None,
        llm_client: Any = None,
        context_manager: Any = None,
    ):
        self.audio_buffer = audio_buffer
        self.llm_client = llm_client
        self.context_manager = context_manager

        self._interrupted_at: float | None = None
        self._partial_response: str = ""

    def trigger(self, partial_response: str = "") -> float:
        """Trigger tear-down cascade.

        Args:
            partial_response: Text generated so far (for context alignment)

        Returns:
            Time taken in milliseconds
        """
        start = time.perf_counter()
        self._interrupted_at = time.time()
        self._partial_response = partial_response

        # Step 1: Flush audio buffer (< 10ms)
        if self.audio_buffer:
            self.audio_buffer.clear()

        # Step 2: Kill LLM (< 10ms)
        # This would send a cancel signal to vLLM
        # For now, just track that we interrupted
        if self.llm_client:
            # llm_client.cancel()
            pass

        # Step 3: Context alignment (< 30ms)
        if self.context_manager:
            # Truncate to words user actually heard
            # This requires knowing exactly when audio stopped
            pass

        return (time.perf_counter() - start) * 1000

    def get_partial_response(self) -> str:
        """Get the portion of response that was actually spoken."""
        return self._partial_response
