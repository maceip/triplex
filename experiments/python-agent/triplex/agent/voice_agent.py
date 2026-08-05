"""Main voice agent loop.

Orchestrates:
1. Audio input → Inherent gate (is this interesting?)
2. Interesting audio → LLM for understanding
3. State machine updates
4. Response generation
5. Compliance throughout
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any, Callable

from .call_state import CallContext, CallPhase, CallStateMachine
from .compliance import DisclosureStatus, check_implied_consent


@dataclass
class AudioChunk:
    """A chunk of audio from the phone line."""

    data: bytes  # PCM 16kHz mono
    duration_ms: float
    timestamp_ms: float


@dataclass
class AgentResponse:
    """Agent's response to be spoken."""

    text: str
    audio: bytes | None = None  # TTS output
    is_final: bool = True
    action: str | None = None  # "hang_up", "transfer", "wait"


class VoiceAgent:
    """Main agent controlling a phone call.

    Key differences from Duplex 2018:
    - Transparent disclosure upfront
    - No secret human fallback
    - LLM handles unexpected responses
    - End-to-end audio models for low latency
    """

    def __init__(
        self,
        context: CallContext,
        on_audio_output: Callable[[bytes], None] | None = None,
        on_state_change: Callable[[CallContext], None] | None = None,
        on_escalation: Callable[[str], None] | None = None,
    ):
        self.state_machine = CallStateMachine(context=context)
        self.on_audio_output = on_audio_output
        self.on_state_change = on_state_change
        self.on_escalation = on_escalation  # Notify user for manual intervention

        # Audio processing state
        self._audio_buffer: list[AudioChunk] = []
        self._is_speaking = False
        self._disclosure_announced_at: float | None = None

    async def start_call(self) -> AgentResponse:
        """Begin the call with disclosure."""
        self.state_machine.context.transition_to(CallPhase.CONNECTING)

        # Simulate connecting delay
        await asyncio.sleep(0.5)

        # Transition to disclosure phase
        self.state_machine.context.transition_to(CallPhase.DISCLOSURE)

        # Generate and speak the opening disclosure
        opening = self.state_machine.get_opening_line()

        # Record that we announced disclosure
        self._disclosure_announced_at = asyncio.get_event_loop().time()
        self.state_machine.context.disclosure_status = DisclosureStatus.ANNOUNCED

        self.state_machine.context.add_turn("agent", opening)

        if self.on_state_change:
            self.on_state_change(self.state_machine.context)

        return AgentResponse(text=opening, action="wait")

    async def process_audio(self, chunk: AudioChunk) -> AgentResponse | None:
        """Process incoming audio from the recipient.

        Flow:
        1. Buffer audio
        2. Run through Inherent gate (is this speech for us?)
        3. If interesting, process with LLM
        4. Update state machine
        5. Generate response
        """
        if self._is_speaking:
            # Don't process while we're talking
            self._audio_buffer.append(chunk)
            return None

        # TODO: Integrate Inherent gate here
        # For now, simplified: assume all audio is speech
        # In production:
        #   mel = extract_mel_spectrogram(chunk.data)
        #   scores = inherent_gate(mel)
        #   if not scores.isInteresting:
        #       return None  # Silence or not for us

        # Simplified: process the audio
        return await self._process_speech(chunk)

    async def _process_speech(self, chunk: AudioChunk) -> AgentResponse | None:
        """Process recognized speech from the recipient."""
        context = self.state_machine.context

        # Handle disclosure phase
        if context.phase == CallPhase.DISCLOSURE:
            return await self._handle_disclosure_response(chunk)

        # Handle task execution phase
        if context.phase == CallPhase.TASK_EXECUTION:
            return await self._handle_task_response(chunk)

        # Handle confirmation phase
        if context.phase == CallPhase.CONFIRMATION:
            return await self._handle_confirmation(chunk)

        return None

    async def _handle_disclosure_response(self, chunk: AudioChunk) -> AgentResponse:
        """Handle recipient's response to our disclosure."""
        context = self.state_machine.context

        # Check for implied consent
        elapsed = asyncio.get_event_loop().time() - (self._disclosure_announced_at or 0)

        # TODO: Get actual transcript from ASR/LLM
        # For now, placeholder
        transcript = "[recipient response]"

        disclosure_status = check_implied_consent(
            status=context.disclosure_status,
            recipient_response=transcript,
            time_elapsed=elapsed,
            config=self.state_machine.disclosure_config,
        )
        context.disclosure_status = disclosure_status

        if disclosure_status == DisclosureStatus.DECLINED:
            # Recipient declined, end call gracefully
            context.fail("Recipient declined the call")
            return AgentResponse(
                text="I understand. Have a great day. Goodbye.",
                action="hang_up",
            )

        if disclosure_status == DisclosureStatus.CONSENT_IMPLIED:
            # Proceed to task execution
            context.transition_to(CallPhase.TASK_EXECUTION)
            return AgentResponse(
                text="Great! Let me help you with that.",
                action="wait",
            )

        # Still waiting for consent
        return AgentResponse(text="I'm sorry, could you repeat that?", action="wait")

    async def _handle_task_response(self, chunk: AudioChunk) -> AgentResponse | None:
        """Handle recipient's response during task execution.

        This is where the LLM shines over Duplex's rigid intents:
        - Unexpected responses get zero-shot reasoning
        - Compromises like "no 7pm but patio 7:15pm" are handled natively
        """
        # TODO: Integrate voice LLM here
        # The LLM should:
        # 1. Understand the recipient's response
        # 2. Update gathered_info
        # 3. Decide next action (ask follow-up, confirm, escalate)
        # 4. Generate response text

        # Placeholder for now
        return AgentResponse(text="Let me check on that.", action="wait")

    async def _handle_confirmation(self, chunk: AudioChunk) -> AgentResponse | None:
        """Handle confirmation of details."""
        # TODO: Implement
        return None

    def escalate(self, reason: str) -> AgentResponse:
        """Escalate to human intervention.

        Unlike Duplex which secretly routed to humans, this is transparent:
        the call ends and the user is notified.
        """
        self.state_machine.context.request_escalation(reason)

        if self.on_escalation:
            self.on_escalation(reason)

        return AgentResponse(
            text="I'm sorry, I'm unable to complete this request. "
                 "A human will follow up with you shortly. Goodbye.",
            action="hang_up",
        )

    async def run(self) -> CallContext:
        """Run the call to completion.

        This is the main entry point for a single call.
        """
        # Start with disclosure
        response = await self.start_call()

        if self.on_audio_output and response.audio:
            self.on_audio_output(response.audio)

        # Main loop would continue here, processing audio
        # from the phone line until the call completes

        return self.state_machine.context


# Integration points (to be connected to real implementations)
async def extract_mel_spectrogram(audio: bytes) -> Any:
    """Extract mel spectrogram from raw PCM audio."""
    # TODO: Use torchaudio or inherent's frontend
    pass


async def run_inherent_gate(mel: Any) -> dict[str, float]:
    """Run the Inherent audio gate to check if audio is interesting.

    Returns:
        Dict with 'isInteresting' score and intent scores
    """
    # TODO: Load and run inherent model
    pass


async def query_voice_llm(
    context: CallContext,
    audio_chunk: AudioChunk | None = None,
    text: str | None = None,
) -> dict[str, Any]:
    """Query a voice LLM (GPT-4o-audio, Gemini Live, etc.).

    The LLM handles:
    - Understanding recipient's speech
    - Reasoning about unexpected responses
    - Generating natural replies
    - Deciding when to escalate
    """
    # TODO: Integrate with OpenAI/Gemini/local LLM
    pass
