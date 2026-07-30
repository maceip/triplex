"""Interactive demo of the Triplex voice agent.

Run with: python -m triplex.demo

Or use: python -m triplex.demo --help
"""

from __future__ import annotations

import asyncio

from triplex.agent.call_state import CallContext, CallPhase, TaskParameters
from triplex.agent.compliance import (
    DisclosureConfig,
    DisclosureStatus,
    generate_disclosure_script,
    get_jurisdiction,
)
from triplex.agent.voice_agent import AgentResponse, VoiceAgent


async def demo_booking_call() -> None:
    """Demo a hair salon booking call."""
    print("=" * 60)
    print("TRIPLEX VOICE AGENT DEMO")
    print("Evolution of Google Duplex (2018)")
    print("=" * 60)
    print()

    # Create task parameters
    task = TaskParameters(
        task_type="appointment",
        description="book a haircut appointment",
        constraints={
            "service": "haircut",
            "date": "2024-01-15",
            "time_preference": "afternoon",
        },
    )

    # Create call context
    context = CallContext(
        task=task,
    )

    print(f"Task: {task.description}")
    print(f"Constraints: {task.constraints}")
    print()

    # Create the agent
    agent = VoiceAgent(
        context=context,
        on_state_change=lambda ctx: print(f"[State: {ctx.phase.value}]"),
        on_escalation=lambda reason: print(f"[ESCALATION NEEDED: {reason}]"),
    )

    # Start the call with disclosure
    print("--- PHASE 1: DISCLOSURE ---")
    response = await agent.start_call()
    print(f"Agent says: \"{response.text}\"")
    print()

    # Simulate recipient accepting
    context.disclosure_status = DisclosureStatus.CONSENT_IMPLIED
    print("[Recipient stays on line - implied consent established]")
    print()

    # Transition to task execution
    context.transition_to(CallPhase.TASK_EXECUTION)
    print("--- PHASE 2: TASK EXECUTION ---")
    print("Agent: Great! Let me help you with that.")
    print("Agent: I'd like to book a haircut for Tuesday afternoon, if you have any openings.")
    print()
    print("[Business: Let me check... We have 2pm or 3:30pm available]")
    print()

    # Demonstrate LLM handling an unexpected response
    print("--- DEMONSTRATION: Handling Unexpected Response ---")
    print("Business: 'We don't have afternoon slots, but I can do 11am or the patio at 7pm'")
    print()
    print("[Duplex 2018: Would FAIL - no pre-mapped intent for this]")
    print("[Triplex 2026: LLM reasons about the compromise]")
    print()
    print("LLM Reasoning:")
    print("  - User preference: afternoon (after 12pm)")
    print("  - Offered: 11am (morning, not preferred) OR patio 7pm (evening)")
    print("  - Patio 7pm is closer to preference")
    print("  - Decision: Offer patio 7pm to user, or ask if they're flexible")
    print()
    print("Agent: 'The afternoon is fully booked, but they have 7pm on the patio.")
    print("       Would that work, or would you prefer I check another day?'")
    print()

    # Complete the call
    context.transition_to(CallPhase.CONFIRMATION)
    print("--- PHASE 3: CONFIRMATION ---")
    print("User confirms: Yes, 7pm patio works")
    print()
    print("Agent: Perfect. I've booked you for Tuesday at 7pm on the patio.")
    print("       Anything else you need to know beforehand?")
    print()

    # Close
    context.transition_to(CallPhase.CLOSING)
    print("--- PHASE 4: CLOSING ---")
    print("Business: Nope, just come a few minutes early. See you then!")
    print()
    print("Agent: Sounds great. Thanks for your help. Goodbye!")
    print()

    # Mark completed
    context.success = True
    context.gathered_info = {
        "date": "2024-01-15",
        "time": "7:00 PM",
        "location": "patio",
        "confirmed": True,
    }
    context.transition_to(CallPhase.COMPLETED)

    print("=" * 60)
    print("CALL COMPLETED SUCCESSFULLY")
    print(f"Result: {context.gathered_info}")
    print("=" * 60)


def demo_compliance() -> None:
    """Demo the compliance disclosure generation."""
    print("\n--- COMPLIANCE DEMO ---\n")

    # California (all-party consent state)
    ca_jurisdiction = get_jurisdiction("CA")
    print(f"Jurisdiction: {ca_jurisdiction.name}")
    print(f"Consent type: {ca_jurisdiction.consent_type}")

    disclosure = generate_disclosure_script(
        task_description="book a haircut appointment",
        jurisdiction=ca_jurisdiction,
        config=DisclosureConfig(),
    )
    print(f"Disclosure: \"{disclosure}\"")
    print()

    # Compare with New York (one-party consent)
    ny_jurisdiction = get_jurisdiction("NY")
    print(f"Jurisdiction: {ny_jurisdiction.name}")
    print(f"Consent type: {ny_jurisdiction.consent_type}")
    print("Same disclosure applied for consistency and ethics.")
    print()


def demo_inherent_gate() -> None:
    """Demo the Inherent audio gate concept."""
    print("\n--- INHERENT GATE DEMO ---\n")
    print("The Inherent model gates audio before ASR/LLM:")
    print()
    print("Input: Mel spectrogram [1, T, 128] from audio chunk")
    print("Output: 13 sigmoid scores")
    print()
    print("  1. isInteresting (is this for the assistant?)")
    print("  2-13. Intent heads (what does the user want?)")
    print()
    print("Latency:")
    print("  EdgeTPU: ~2ms")
    print("  MLX (Apple Silicon): ~2ms")
    print("  GPU: ~3ms")
    print("  CPU: ~6ms")
    print()
    print("Compare to Duplex 2018:")
    print("  ASR alone: 100-400ms")
    print("  Plus intent classification: +50-200ms")
    print("  Total: 150-600ms BEFORE understanding")
    print()
    print("Triplex with Inherent:")
    print("  Gate check: 2-6ms")
    print("  Only run ASR/LLM if interesting: ~300ms total")
    print()


async def main() -> None:
    """Run all demos."""
    demo_compliance()
    demo_inherent_gate()
    await demo_booking_call()

    print("\n--- KEY IMPROVEMENTS OVER DUPLEX 2018 ---\n")
    print("1. ELIMINATING DECEPTION")
    print("   Duplex: Engineered to fool humans, no disclosure")
    print("   Triplex: Upfront announcement, implied consent")
    print()
    print("2. REMOVING THE SHADOW CALL CENTER")
    print("   Duplex: 25-40% of calls handled by humans")
    print("   Triplex: Transparent failure, user alerted")
    print()
    print("3. BYPASSING THE RIGID INTENT TRAP")
    print("   Duplex: Cascaded DM fails on surprises")
    print("   Triplex: LLM zero-shot handles unexpected responses")
    print()
    print("4. SOLVING THE LATENCY TRAP")
    print("   Duplex: 2-4s with synthetic 'um' stalling")
    print("   Triplex: ~300ms via end-to-end audio models")
    print()


if __name__ == "__main__":
    asyncio.run(main())
