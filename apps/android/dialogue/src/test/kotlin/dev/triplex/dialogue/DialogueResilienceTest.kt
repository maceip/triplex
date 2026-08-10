package dev.triplex.dialogue

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the agent does when things go wrong mid-call.
 *
 * Every case here has a live human on the other end of the line, which is why
 * none of them are allowed to end in silence and none of them are allowed to
 * end in a made-up answer.
 */
class DialogueResilienceTest {

    private val caller = listOf(
        "Hi, I'm calling about the invoice you sent last week.",
        "It's invoice four-two-one-nine, and the total looks wrong.",
        "Can someone call me back on this number?",
    )

    private val nano = mapOf(
        "invoice you sent" to "I can take a message about that. Which invoice number is it?",
        "four-two-one-nine" to
            "Thanks — invoice four two one nine, and you think the total is incorrect. " +
                "Is there a figure you were expecting?",
        "call me back" to "Of course. I'll pass on that you'd like a call back on this number.",
    )

    private fun screening(budget: DialogueBudget = DialogueBudget()) =
        DialogueBrief.screening(greeting = "Hi, Triplex screening assistant.", budget = budget)

    /**
     * AICore drops a request now and then. One dropped request must not hang up
     * on someone mid-sentence — the agent asks them to repeat and carries on.
     */
    @Test
    fun a_single_reasoner_failure_buys_a_turn_and_the_call_continues() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(nano, failOnTurns = setOf(2))
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(screening())

        assertEquals(StopReason.CALLER_SILENT, outcome.stop)
        assertEquals(1, outcome.reasonerFailures)
        assertEquals(1, outcome.fallbacksSpoken)
        assertTrue(outcome.completedCleanly, "one flake is not a broken call")

        assertContains(
            transport.spoken,
            "Sorry, I did not quite catch that. Could you say it once more?",
        )
        // The holding line said nothing about the invoice — a fallback that
        // invents content is worse than one that admits it missed the turn.
        val fallback = observer.ofType<DialogueEvent.FallbackSpoken>().single()
        assertFalse(fallback.text.contains("invoice", ignoreCase = true))
        assertFalse(fallback.text.contains("four", ignoreCase = true))
    }

    /** Nothing sounds more like a machine than the same sentence twice. */
    @Test
    fun consecutive_fallbacks_do_not_repeat_the_same_sentence() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(nano, failOnTurns = setOf(1, 2))

        CallDialogue(reasoner, transport).run(screening())

        val fallbacks = transport.spoken.filter { it.startsWith("Sorry") || it.startsWith("Apologies") }
        assertEquals(2, fallbacks.size)
        assertEquals(fallbacks.toSet().size, fallbacks.size, "fallback lines are cycled, not repeated")
    }

    /**
     * A model that has failed twice running is not about to answer the third
     * time. The agent stops trying and says so.
     */
    @Test
    fun a_reasoner_that_keeps_failing_ends_the_call_honestly() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(nano, failOnTurns = setOf(1, 2, 3))
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(screening())

        assertEquals(StopReason.REASONER_UNAVAILABLE, outcome.stop)
        assertEquals(3, outcome.reasonerFailures)
        assertEquals(2, outcome.fallbacksSpoken, "bounded by maxConsecutiveReasonerFailures")
        assertEquals(0, outcome.reasonedTurns)
        assertFalse(outcome.completedCleanly)

        val lastLine = transport.spoken.last()
        assertContains(lastLine, "having trouble following the call")
        assertContains(lastLine, "someone will get back to you")

        // The failures were counted consecutively, which is what the budget
        // is actually spent against.
        assertEquals(
            listOf(1, 2, 3),
            observer.ofType<DialogueEvent.ReasonerFailed>().map { it.consecutive },
        )
    }

    /** A good turn between two failures resets the budget — it is not a total. */
    @Test
    fun the_failure_budget_counts_consecutive_failures_not_lifetime_ones() = runTest {
        val longCall = caller + listOf("Also, is the office open on Friday?", "Great, thanks.")
        val extendedNano = nano + mapOf(
            "open on Friday" to "I'm not able to confirm the opening hours, but I'll ask and pass it on.",
            "Great, thanks" to "You're welcome. I'll make sure they get the message. Goodbye.",
        )
        val transport = ScriptedCaller(longCall)
        // Fail, succeed, fail, succeed: never two in a row.
        val reasoner = ReplayReasoner(extendedNano, failOnTurns = setOf(1, 3))

        val outcome = CallDialogue(reasoner, transport).run(screening())

        assertEquals(StopReason.CALLER_SILENT, outcome.stop)
        assertEquals(2, outcome.reasonerFailures)
        assertEquals(2, outcome.fallbacksSpoken)
        assertTrue(outcome.reasonedTurns >= 3, "the call kept going")
    }

    /**
     * A phone with no Nano must not discover it three turns in. The check runs
     * once, after the scripted greeting, and the call closes immediately.
     */
    @Test
    fun a_missing_model_is_found_before_the_conversation_starts() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(nano, availableResult = false)
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(screening())

        assertEquals(StopReason.REASONER_UNAVAILABLE, outcome.stop)
        assertEquals(0, outcome.reasonedTurns)
        assertEquals(1, reasoner.availabilityChecks, "checked once, not once per turn")

        // The caller heard a greeting and an honest sign-off; nothing else.
        assertEquals(2, transport.spoken.size)
        assertContains(transport.spoken.last(), "I will pass on that you called")
    }

    @Test
    fun an_availability_probe_that_throws_is_treated_as_no_model() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(nano, availabilityThrows = true)
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(screening())

        assertEquals(StopReason.REASONER_UNAVAILABLE, outcome.stop)
        // The distinction between "unavailable" and "the check exploded" is
        // recorded even though the call does the same thing about it.
        assertContains(
            observer.ofType<DialogueEvent.ReasonerFailed>().single().reason,
            "AICore feature status unavailable",
        )
    }

    /**
     * Model output with nothing speakable in it — a bare bullet list, a stage
     * direction — is a failed turn, not an empty reply. Speaking it would put
     * "asterisk asterisk" on a live call.
     */
    @Test
    fun unspeakable_model_output_counts_as_a_failure_rather_than_being_spoken() = runTest {
        val transport = ScriptedCaller(caller)
        val reasoner = ReplayReasoner(
            nano + mapOf("invoice you sent" to "**\n\n---\n\n* \n"),
        )
        val observer = RecordingObserver()

        val outcome = CallDialogue(reasoner, transport, observer).run(screening())

        assertEquals(1, outcome.reasonerFailures)
        assertEquals(1, outcome.fallbacksSpoken)
        assertContains(
            observer.ofType<DialogueEvent.ReasonerFailed>().single().reason,
            "no speakable text",
        )
        assertTrue(transport.spoken.none { it.contains("*") }, "no markdown reached the wire")
    }

    /** A caller who sets the phone down gets let go, not listened to forever. */
    @Test
    fun silence_closes_the_call_with_a_spoken_line() = runTest {
        val transport = ScriptedCaller(caller, silentAfterTurn = 1)

        val outcome = CallDialogue(ReplayReasoner(nano), transport).run(screening())

        assertEquals(StopReason.CALLER_SILENT, outcome.stop)
        assertEquals(1, outcome.reasonedTurns)
        assertEquals(
            "I could not hear anything further, so I will let you go. Goodbye.",
            transport.spoken.last(),
        )
    }

    /** A caller who hangs up gets nothing further said at them. */
    @Test
    fun a_hangup_ends_the_loop_without_speaking_into_a_dead_line() = runTest {
        val transport = object : DialogueTransport {
            var active = true
            val spoken = mutableListOf<String>()
            override fun isActive(): Boolean = active
            override suspend fun speak(text: String): SpeechResult {
                if (!active) return SpeechResult.CALL_ENDED
                spoken += text
                return SpeechResult.DELIVERED
            }
            override suspend fun awaitCallerReply(): String? {
                active = false // the caller hung up while the agent listened
                return null
            }
        }

        val outcome = CallDialogue(ReplayReasoner(nano), transport).run(screening())

        assertEquals(StopReason.CALL_ENDED, outcome.stop)
        assertEquals(listOf("Hi, Triplex screening assistant."), transport.spoken)
    }

    /** Media that stops carrying audio ends the call rather than talking to nobody. */
    @Test
    fun a_broken_media_path_stops_the_call_instead_of_talking_into_it() = runTest {
        val transport = FailingSpeechTransport(failFromTurn = 2, utterances = caller)

        val outcome = CallDialogue(ReplayReasoner(nano), transport).run(screening())

        assertEquals(StopReason.SPEECH_FAILED, outcome.stop)
        assertFalse(outcome.completedCleanly)
    }

    /**
     * The wall clock is a ceiling on the whole call, checked before a turn
     * starts so the agent never opens one it cannot finish.
     */
    @Test
    fun the_wall_clock_budget_closes_a_call_that_runs_long() = runTest {
        val clock = TestClock()
        // Each exchange costs 40 s of call time — a slow, real support line.
        val transport = ScriptedCaller(
            caller + listOf("One more thing.", "And another."),
            onExchange = { clock.advance(40_000L) },
        )
        val extendedNano = nano + mapOf(
            "One more thing" to "Go ahead, I'm listening.",
            "And another" to "Noted, I'll pass that on too.",
        )

        val outcome = CallDialogue(
            ReplayReasoner(extendedNano),
            transport,
            clock = clock,
        ).run(screening(DialogueBudget(maxTurns = 10, maxDurationMs = 120_000L)))

        assertEquals(StopReason.TIME_BUDGET, outcome.stop)
        assertTrue(outcome.durationMs >= 120_000L)
        assertEquals(
            "Thank you. I have the details and I will pass them along. Goodbye.",
            transport.spoken.last(),
        )
    }

    /** One instance serves consecutive calls; state must not leak between them. */
    @Test
    fun a_reused_dialogue_starts_the_next_call_from_zero() = runTest {
        val plan = screening()
        val first = ScriptedCaller(caller)
        val second = ScriptedCaller(caller)
        val dialogue = CallDialogue(ReplayReasoner(nano), first)

        val firstOutcome = dialogue.run(plan)
        // A second call, same instance, fresh transport.
        val secondOutcome = CallDialogue(ReplayReasoner(nano), second).run(plan)

        assertEquals(firstOutcome.reasonedTurns, secondOutcome.reasonedTurns)
        assertEquals(firstOutcome.transcript.size, secondOutcome.transcript.size)
    }
}
