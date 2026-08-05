"""Call state machine for tracking conversation progress.

Unlike Duplex's rigid cascaded dialogue manager, Triplex uses
a flexible state machine that delegates to LLM for unexpected transitions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any
from uuid import uuid4

from .compliance import DisclosureConfig, DisclosureStatus, generate_disclosure_script


class CallPhase(Enum):
    """High-level phases of a call."""

    INITIALIZING = "initializing"
    CONNECTING = "connecting"
    DISCLOSURE = "disclosure"
    TASK_EXECUTION = "task_execution"
    CONFIRMATION = "confirmation"
    CLOSING = "closing"
    COMPLETED = "completed"
    FAILED = "failed"
    HUMAN_ESCALATION = "human_escalation"


@dataclass
class TaskParameters:
    """User-provided parameters for the call task."""

    task_type: str  # "appointment", "reservation", "inquiry"
    description: str  # Human-readable task description
    constraints: dict[str, Any] = field(default_factory=dict)
    preferences: dict[str, Any] = field(default_factory=dict)

    # Example for appointment booking:
    # task_type = "appointment"
    # description = "book a hair cut"
    # constraints = {"date": "2024-01-15", "time_preference": "morning"}
    # preferences = {"stylist": "any", "service": "haircut"}


@dataclass
class CallContext:
    """Full context for an ongoing call."""

    call_id: str = field(default_factory=lambda: str(uuid4())[:8])
    phase: CallPhase = CallPhase.INITIALIZING
    disclosure_status: DisclosureStatus = DisclosureStatus.PENDING

    # Task information
    task: TaskParameters | None = None

    # Conversation history (for LLM context)
    transcript: list[dict[str, Any]] = field(default_factory=list)
    turn_count: int = 0

    # Extracted information from the call
    gathered_info: dict[str, Any] = field(default_factory=dict)

    # Outcome
    success: bool | None = None
    failure_reason: str | None = None

    # Timing
    started_at: datetime = field(default_factory=datetime.now)
    phase_entered_at: datetime = field(default_factory=datetime.now)

    # Lidar for things the agent couldn't handle
    escalation_needed: bool = False
    escalation_reason: str | None = None

    def add_turn(self, speaker: str, text: str, audio_duration_ms: float | None = None) -> None:
        """Add a turn to the conversation transcript."""
        self.transcript.append({
            "speaker": speaker,  # "agent" or "recipient"
            "text": text,
            "timestamp": datetime.now().isoformat(),
            "audio_duration_ms": audio_duration_ms,
        })
        self.turn_count += 1

    def transition_to(self, new_phase: CallPhase) -> None:
        """Transition to a new call phase."""
        self.phase = new_phase
        self.phase_entered_at = datetime.now()

    def request_escalation(self, reason: str) -> None:
        """Mark that human intervention is needed.

        Unlike Duplex which secretly routed to humans, Triplex
        is transparent: the user is notified and the call ends gracefully.
        """
        self.escalation_needed = True
        self.escalation_reason = reason
        self.transition_to(CallPhase.HUMAN_ESCALATION)


@dataclass
class CallStateMachine:
    """State machine managing call progression."""

    context: CallContext
    disclosure_config: DisclosureConfig = field(default_factory=DisclosureConfig)

    def get_opening_line(self) -> str:
        """Generate the opening disclosure line."""
        if self.context.task is None:
            raise ValueError("Task must be set before getting opening line")

        from .compliance import get_jurisdiction

        jurisdiction = get_jurisdiction()  # Default jurisdiction
        return generate_disclosure_script(
            task_description=self.context.task.description,
            jurisdiction=jurisdiction,
            config=self.disclosure_config,
        )

    def can_proceed_to_task(self) -> bool:
        """Check if we can proceed from disclosure to task execution."""
        return self.context.disclosure_status == DisclosureStatus.CONSENT_IMPLIED

    def should_escalate(self) -> bool:
        """Check if human escalation is needed."""
        return self.context.escalation_needed

    def fail(self, reason: str) -> None:
        """Mark the call as failed with a reason."""
        self.context.success = False
        self.context.failure_reason = reason
        self.context.transition_to(CallPhase.FAILED)

    def complete(self, result: dict[str, Any]) -> None:
        """Mark the call as successfully completed."""
        self.context.success = True
        self.context.gathered_info = result
        self.context.transition_to(CallPhase.COMPLETED)


# Response type the agent can return for unexpected situations
@dataclass
class UnexpectedResponse:
    """Represents when the recipient gives an unexpected response.

    The 2018 Duplex flaw: Cascaded dialogue manager couldn't handle
    responses that didn't match pre-mapped intents.

    The Triplex fix: These are passed to the LLM for zero-shot reasoning.
    """

    recipient_text: str
    expected_intents: list[str]
    what_we_got: str  # Free-form description
    requires_reasoning: bool = True


# Example: Restaurant says "No 7pm, but patio 7:15pm"
# Duplex would fail; Triplex LLM can reason:
# "The patio offers 7:15pm. User preference was 7pm. Is this acceptable?"
# Then ask user or check constraints.
