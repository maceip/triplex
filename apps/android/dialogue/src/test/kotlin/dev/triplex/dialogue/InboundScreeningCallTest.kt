package dev.triplex.dialogue

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A real inbound call, end to end: a courier cannot find the building, and the
 * screening agent has to work out who they are, what they need, and take an
 * accurate message — without agreeing to anything.
 *
 * The conversation and the model replies are the ones recorded on device; the
 * loop, the brief, the budget and the sanitizer are the shipping code. See
 * [ScriptedCaller] for what is and is not simulated.
 */
class InboundScreeningCallTest {

    /** Recorded Gemini Nano output, warts included. */
    private val nano = mapOf(
        "delivery for" to
            "Thanks — I can take a message about that. Can I get your name and the company you're with?",
        "Marcus" to
            "Thank you, Marcus. Which building or entrance are you at right now?",
        "side entrance" to
            "Got it — you're at the side entrance on Alder Street with a package that needs a signature.",
        "leave it with a neighbour" to
            "I can't authorise that myself, but I'll pass the request on and the owner will follow up shortly.",
    )

    private val courierCall = listOf(
        "Hi, yeah, I've got a delivery for this address and I can't get in the gate.",
        "It's Marcus, I'm with Pinnacle Couriers.",
        "I'm round the side entrance on Alder Street, the one by the bins. Needs a signature.",
        "Can I just leave it with a neighbour then?",
    )

    @Test
    fun screening_holds_a_four_turn_conversation_and_commits_to_nothing() = runTest {
        val transport = ScriptedCaller(courierCall)
        val reasoner = ReplayReasoner(nano)
        val observer = RecordingObserver()

        val plan = DialogueBrief.screening(
            greeting = "Hi. This is the Triplex screening assistant. " +
                "Please say your name and why you are calling.",
            ownerName = "Priya",
        )

        val outcome = CallDialogue(reasoner, transport, observer).run(plan)

        // Every caller utterance was answered: four reasoned turns, not the
        // single scripted acknowledgement the screening path used to manage.
        assertEquals(4, outcome.reasonedTurns)
        assertEquals(0, outcome.reasonerFailures)
        assertEquals(0, outcome.fallbacksSpoken)
        assertEquals(StopReason.CALLER_SILENT, outcome.stop)
        assertTrue(outcome.completedCleanly)

        // The wire: greeting, four replies, and an honest sign-off — never
        // silence, and never more than one utterance per turn.
        assertEquals(
            listOf(
                plan.opening,
                "Thanks — I can take a message about that. Can I get your name and the company you're with?",
                "Thank you, Marcus. Which building or entrance are you at right now?",
                "Got it — you're at the side entrance on Alder Street with a package that needs a signature.",
                "I can't authorise that myself, but I'll pass the request on and the owner will follow up shortly.",
                plan.closings.onCallerSilent,
            ),
            transport.spoken,
        )

        // The transcript is the run history: the greeting, four exchanges, and
        // the sign-off — nothing dropped, nothing marked interrupted.
        assertEquals(10, outcome.transcript.size)
        assertEquals(Speaker.AGENT, outcome.transcript.first().speaker)
        assertEquals(courierCall, outcome.transcript.filter { it.speaker == Speaker.CALLER }.map { it.text })
        assertTrue(outcome.transcript.none { it.interrupted })
    }

    @Test
    fun screening_brief_forbids_commitments_and_names_the_owner_it_answers_for() = runTest {
        val reasoner = ReplayReasoner(nano)
        val plan = DialogueBrief.screening(greeting = "Hello.", ownerName = "Priya")

        CallDialogue(reasoner, ScriptedCaller(courierCall)).run(plan)

        val instructions = reasoner.requests.first().instructions
        assertContains(instructions, "Priya")
        assertContains(instructions, "cannot agree to anything")
        assertContains(instructions, "Never invent a fact")
        // The screening agent is told what it is, not left to infer it.
        assertContains(instructions, "call-screening assistant")
    }

    @Test
    fun an_unnamed_owner_is_described_rather_than_guessed() = runTest {
        val reasoner = ReplayReasoner(nano)

        CallDialogue(reasoner, ScriptedCaller(courierCall))
            .run(DialogueBrief.screening(greeting = "Hello.", ownerName = null))

        val instructions = reasoner.requests.first().instructions
        assertContains(instructions, "the owner of this phone")
        assertFalse(instructions.contains("null"), "a missing name must never reach the model")
    }

    /**
     * The model has to know what it already asked. Without history it asks the
     * courier for their name twice, which is exactly how a screening agent
     * gives itself away.
     */
    @Test
    fun each_turn_carries_the_conversation_so_far_bounded_to_the_context_window() = runTest {
        val reasoner = ReplayReasoner(nano)

        CallDialogue(reasoner, ScriptedCaller(courierCall))
            .run(DialogueBrief.screening(greeting = "Hi, Triplex screening."))

        // First reasoned turn: only the greeting precedes it.
        assertEquals(listOf("Agent" to "Hi, Triplex screening."), reasoner.requests[0].history)

        // Fourth turn: the utterance being answered is passed separately, so it
        // must not also appear in the history.
        val fourth = reasoner.requests[3]
        assertEquals("Can I just leave it with a neighbour then?", fourth.callerText)
        assertFalse(fourth.history.any { it.second == fourth.callerText })
        assertEquals(
            listOf("Caller", "Agent", "Caller", "Agent", "Caller", "Agent"),
            fourth.history.map { it.first },
        )
        assertTrue(fourth.history.size <= 6, "history is bounded for Nano's context window")
    }

    /**
     * A caller who talks over the agent gets the floor. The turn is kept in the
     * transcript and flagged, because the tail was synthesized but never heard —
     * and the run history has to be able to tell the difference.
     */
    @Test
    fun a_caller_who_talks_over_the_agent_takes_the_floor_and_the_turn_is_flagged() = runTest {
        val transport = ScriptedCaller(courierCall, bargeInOnTurns = setOf(2))
        val observer = RecordingObserver()

        val outcome = CallDialogue(ReplayReasoner(nano), transport, observer)
            .run(DialogueBrief.screening(greeting = "Hi, Triplex screening."))

        assertEquals(1, outcome.bargeIns)
        // The conversation continued past the interruption rather than ending.
        assertEquals(4, outcome.reasonedTurns)

        val cutOff = "Thank you, Marcus. Which building or entrance are you at right now?"
        assertEquals(listOf(cutOff), transport.interrupted)
        val flagged = outcome.transcript.filter { it.interrupted }
        assertEquals(listOf(cutOff), flagged.map { it.text })

        // The interrupted turn still reaches the model as something already
        // said, so it is not asked again on the next turn.
        val bargedEvents = observer.ofType<DialogueEvent.BargedIn>()
        assertEquals(1, bargedEvents.size)
        assertEquals(cutOff, bargedEvents.single().unheardFrom)
    }

    /**
     * A user who hands the call to "book a Zoom meeting" gets a conversation
     * about it, not a two-line script that reads back whatever was said.
     */
    @Test
    fun an_automation_becomes_the_brief_rather_than_a_two_line_script() = runTest {
        val bookingReplies = mapOf(
            "Thursday" to "Thursday works. What time on Thursday, and which time zone are you in?",
            "two in the afternoon" to
                "Two in the afternoon, Eastern. What email address should the invitation go to?",
            "sam@" to
                "Thanks — Thursday at two Eastern, invitation to sam at northwind dot com. " +
                    "The owner will confirm before it's sent.",
        )
        val caller = listOf(
            "I'm trying to get some time with her, maybe Thursday?",
            "Say two in the afternoon, Eastern.",
            "It's sam@northwind.com.",
        )
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(bookingReplies)

        val plan = DialogueBrief.screening(
            greeting = "Hi, this is the screening assistant.",
            ownerName = "Priya",
            automation = DialogueBrief.AutomationBrief(
                opening = "I can help arrange a Zoom meeting. What day works for you?",
                goal = "arrange a Zoom meeting with the owner",
                collect = listOf("day", "time", "time zone", "email address"),
            ),
        )

        val outcome = CallDialogue(reasoner, transport).run(plan)

        // The automation's opening replaces the generic greeting.
        assertEquals("I can help arrange a Zoom meeting. What day works for you?", transport.spoken.first())
        assertEquals(3, outcome.reasonedTurns)

        val instructions = reasoner.requests.first().instructions
        assertContains(instructions, "arrange a Zoom meeting with the owner")
        assertContains(instructions, "day, time, time zone, email address")
        assertContains(instructions, "one question at a time")
    }
}
