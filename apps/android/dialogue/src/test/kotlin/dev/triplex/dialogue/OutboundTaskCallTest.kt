package dev.triplex.dialogue

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A real outbound call: the user asked Triplex to return a monitor, and the
 * agent has to get through a support line, state the facts it was given, and
 * land on an outcome — without accepting a different one on the user's behalf.
 *
 * This is the path that used to run exactly two reasoned turns and then stop
 * mid-conversation regardless of where the call had got to.
 */
class OutboundTaskCallTest {

    private val supportAgent = listOf(
        "Samsung support, this is Dana, can I get the order number?",
        "Okay, I see it — 32-inch monitor, delivered the eleventh. What's wrong with it?",
        "I can do a store credit for that today, would that work?",
        "Alright, I've raised a return, you'll get a shipping label by email within the hour.",
    )

    /** Recorded Nano output for the support conversation. */
    private val nano = mapOf(
        "order number" to "Of course — the order number is SG-4471-2290.",
        "What's wrong with it" to
            "There's a vertical line of dead pixels down the right side of the panel, " +
                "and it was there out of the box.",
        "store credit" to
            "I'm asked to arrange a refund rather than credit, so I'll note the credit offer " +
                "and let the customer decide. Can a refund be raised?",
        "shipping label" to
            "That's great, thank you. To confirm: a return is raised and a shipping label " +
                "comes by email within the hour.",
    )

    private fun returnPlan(budget: DialogueBudget = DialogueBudget(maxTurns = 8)) =
        DialogueBrief.outboundTask(
            goal = "return a 32-inch monitor and get a refund",
            facts = mapOf(
                "order number" to "SG-4471-2290",
                "reason" to "a column of dead pixels, present on arrival",
                "desired outcome" to "a refund to the original payment method",
            ),
            budget = budget,
        )

    @Test
    fun the_agent_works_a_support_call_to_an_outcome_over_four_turns() = runTest {
        val transport = ScriptedCaller(supportAgent)
        val reasoner = ReplayReasoner(nano)
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(returnPlan())

        assertEquals(4, outcome.reasonedTurns)
        assertEquals(0, outcome.reasonerFailures)
        assertEquals(StopReason.CALLER_SILENT, outcome.stop)
        assertTrue(outcome.completedCleanly)

        // The opening is scripted and states the facts the user supplied — the
        // one utterance whose exact wording is knowable before dialling.
        val opening = transport.spoken.first()
        assertContains(opening, "return a 32-inch monitor and get a refund")
        assertContains(opening, "The order number is SG-4471-2290.")
        assertTrue(opening.endsWith("Can you help me with that?"))

        // The agent declined to accept store credit on the user's behalf.
        assertTrue(
            transport.spoken.any { it.contains("let the customer decide") },
            "the agent must report an alternative offer, not accept it",
        )
    }

    @Test
    fun the_brief_pins_the_facts_the_agent_is_allowed_to_state() = runTest {
        val reasoner = ReplayReasoner(nano)

        CallDialogue(reasoner, ScriptedCaller(supportAgent)).run(returnPlan())

        val instructions = reasoner.requests.first().instructions
        assertContains(instructions, "These are the only facts you may state")
        assertContains(instructions, "order number: SG-4471-2290")
        assertContains(instructions, "never accept a different one on the customer's behalf")
        assertContains(instructions, "Never invent a fact")
    }

    /**
     * The last reasoned turn is told to land the call. Without this the agent
     * asks a fresh question and then gets cut off by its own turn budget, which
     * is how the two-turn outbound path used to sound.
     */
    @Test
    fun the_final_turn_is_told_to_close_and_the_budget_is_respected() = runTest {
        val transport = ScriptedCaller(supportAgent)
        val reasoner = ReplayReasoner(nano)

        val outcome = CallDialogue(reasoner, transport)
            .run(returnPlan(DialogueBudget(maxTurns = 3)))

        assertEquals(3, outcome.reasonedTurns)
        assertEquals(StopReason.TURN_BUDGET, outcome.stop)

        // Only the last turn carries the closing instruction.
        val closingHint = "This is your last turn."
        assertEquals(
            listOf(false, false, true),
            reasoner.requests.map { it.instructions.contains(closingHint) },
        )

        // And the call still ends with something said out loud, not silence.
        assertEquals(
            "Thank you for your time. I have what I need and my customer will follow up. Goodbye.",
            transport.spoken.last(),
        )
    }

    /**
     * Outbound the agent is transacting for someone. A reply generated around a
     * misheard turn can accept a store credit instead of a refund, so the first
     * reasoner failure ends the call honestly rather than buying a turn.
     */
    @Test
    fun outbound_fails_closed_on_the_first_reasoner_failure() = runTest {
        val transport = ScriptedCaller(supportAgent)
        val reasoner = ReplayReasoner(nano, failOnTurns = setOf(2))

        val outcome = CallDialogue(reasoner, transport).run(returnPlan())

        assertEquals(StopReason.REASONER_UNAVAILABLE, outcome.stop)
        assertEquals(1, outcome.reasonedTurns, "the turn before the failure landed")
        assertEquals(1, outcome.reasonerFailures)
        assertEquals(0, outcome.fallbacksSpoken, "outbound must not stall for time")
        assertFalse(outcome.completedCleanly)

        // It said why it was stopping instead of going quiet on a stranger.
        assertEquals(
            "I am sorry, I am having trouble on this line. I will have the customer call " +
                "back directly rather than take up more of your time. Goodbye.",
            transport.spoken.last(),
        )
    }

    /**
     * Facts with no value are dropped rather than spoken as empty phrases. The
     * old opening builder said "The reason is ." when a task omitted one.
     */
    @Test
    fun blank_task_parameters_never_reach_the_wire() {
        val opening = DialogueBrief.outboundOpening(
            goal = "change a reservation",
            facts = mapOf(
                "reservation name" to "Okafor",
                "reason" to "",
                "new time" to "   ",
            ),
        )

        assertEquals(
            "Hello. I am an assistant calling on behalf of a customer about change a reservation. " +
                "The reservation name is Okafor. Can you help me with that?",
            opening,
        )
    }

    /** Three specifics is as much as anyone follows before the question. */
    @Test
    fun an_opening_states_at_most_three_specifics() {
        val opening = DialogueBrief.outboundOpening(
            goal = "return an item",
            facts = mapOf(
                "order number" to "A1",
                "reason" to "B2",
                "desired outcome" to "C3",
                "account number" to "D4",
                "delivery date" to "E5",
            ),
        )

        assertFalse(opening.contains("D4"))
        assertFalse(opening.contains("E5"))
        assertContains(opening, "C3")
    }
}
