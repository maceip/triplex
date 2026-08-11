package dev.triplex.dialogue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The multi-turn conversation loop, for inbound screening and outbound tasks
 * alike.
 *
 * ## Why one loop for both directions
 *
 * Before this existed, outbound calls ran two hard-coded reasoned turns inside
 * the SIP engine and inbound screening ran a fixed two-line script — greet,
 * listen once, acknowledge. Neither could hold a conversation, and the
 * difference between them was an accident of which one was written first, not a
 * product decision. Everything that genuinely differs between the two
 * directions is data: the brief, the budget, and what happens when the model
 * fails. That data is [DialoguePlan]; the control flow is the same, so it is
 * written once and tested once.
 *
 * ## What the loop guarantees
 *
 * 1. **The agent never goes silent.** Every exit is spoken. A call that runs
 *    out of turns, loses the reasoner, or hears nothing gets a closing line,
 *    not dead air.
 * 2. **The agent never invents.** When the reasoner cannot answer, the loop
 *    speaks a content-free holding line or an honest close — chosen by
 *    [FallbackPolicy] — and never fabricates a reply on the model's behalf.
 * 3. **The caller always wins the floor.** Barge-in is the transport's job
 *    ([SpeechResult.INTERRUPTED]); the loop's job is to not fight it. An
 *    interrupted turn is recorded as interrupted and the loop listens next,
 *    rather than re-speaking the tail the caller cut off.
 * 4. **The call is bounded.** Turns, wall-clock time, and consecutive reasoner
 *    failures all have ceilings. The wall clock is a real bound and not just a
 *    sample taken between turns: waiting for the caller and waiting for the
 *    model are both capped at whatever is left of the budget, because those
 *    are the two steps that can block for a long time and a check that only
 *    runs between them would let a single slow turn overrun by minutes.
 *
 * The loop owns *when* things are said. [DialogueTransport] owns how audio gets
 * to the wire and [CallReasoner] owns what a reply says — this class holds
 * neither, which is what makes a real conversation testable on a bare JVM.
 */
class CallDialogue(
    private val reasoner: CallReasoner,
    private val transport: DialogueTransport,
    private val observer: DialogueObserver = DialogueObserver.None,
    /** Overridable so budget expiry is exercised without waiting three minutes. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs [plan] to completion and returns what happened.
     *
     * Suspends for the length of the call. Cancellation propagates: a hangup
     * that cancels the enclosing scope stops the loop at its next suspension
     * point without speaking a closing line, because there is no longer anyone
     * to say it to.
     */
    suspend fun run(plan: DialoguePlan): DialogueOutcome {
        val session = Session(plan)
        observer.onEvent(DialogueEvent.Opened(plan.direction, plan.opening, plan.budget))

        // The opening is scripted, so it goes out before the model is consulted
        // at all. The caller hears a human-paced greeting while availability is
        // still being resolved, instead of a second of silence.
        val openingResult = session.speak(plan.opening, turn = 0)
        if (!openingResult.reachedCaller) {
            return session.finish(
                if (openingResult == SpeechResult.CALL_ENDED) StopReason.CALL_ENDED
                else StopReason.SPEECH_FAILED
            )
        }

        // Availability can block: the production reasoner starts a Nano
        // download when the feature is only DOWNLOADABLE. That must not leave
        // the caller in silence past the call's wall-clock budget after the
        // opening has already gone out — same bound as listen/reply.
        val availability = session.withinBudget { Box(session.reasonerIsAvailable()) }
            ?: return session.outOfTime()
        if (!availability.value) {
            session.speakClosing(plan.closings.onReasonerLost)
            return session.finish(StopReason.REASONER_UNAVAILABLE)
        }

        while (true) {
            when (val gate = session.budgetCheck()) {
                is Gate.Stop -> {
                    session.speakClosing(gate.closing)
                    return session.finish(gate.reason)
                }
                Gate.Continue -> Unit
            }

            // Waiting for the caller is capped at what is left of the call's
            // budget. A transport whose own silence timeout is longer than the
            // remaining time would otherwise spend it all here.
            val listened = session.withinBudget { Box(session.listen()) }
                ?: return session.outOfTime()
            val callerText = listened.value
            if (callerText == null) {
                val reason =
                    if (!transport.isActive()) StopReason.CALL_ENDED else StopReason.CALLER_SILENT
                if (reason == StopReason.CALLER_SILENT) {
                    session.speakClosing(plan.closings.onCallerSilent)
                }
                return session.finish(reason)
            }

            // So is waiting for the model. An on-device model under memory
            // pressure can take many seconds, and every one of them is dead
            // air; past the budget the call closes instead of accruing more.
            val reasoned = session.withinBudget { Box(session.reason(callerText)) }
                ?: return session.outOfTime(reasonerOverran = true)

            when (val reply = reasoned.value) {
                is Reply.Spoken -> {
                    // Speaking is deliberately *not* cut off at the deadline.
                    // It is already bounded — `SpokenReply` caps an utterance
                    // at about fifteen seconds — and stopping mid-word to save
                    // a few of them is worse for the person listening than a
                    // slightly long call. The next budget check ends it.
                    val result = session.speak(reply.text, session.turnNumber)
                    if (!result.reachedCaller) {
                        return session.finish(
                            if (result == SpeechResult.CALL_ENDED) StopReason.CALL_ENDED
                            else StopReason.SPEECH_FAILED
                        )
                    }
                }
                Reply.Exhausted -> {
                    session.speakClosing(plan.closings.onReasonerLost)
                    return session.finish(StopReason.REASONER_UNAVAILABLE)
                }
            }
        }
    }

    /**
     * Distinguishes "the operation returned null" from "the operation ran out
     * of time", which `withTimeoutOrNull` alone cannot: both come back as null.
     */
    private class Box<T>(val value: T)

    private sealed interface Gate {
        data object Continue : Gate
        data class Stop(val reason: StopReason, val closing: String) : Gate
    }

    private sealed interface Reply {
        /** Something to say — either a reasoned reply or a holding line. */
        data class Spoken(val text: String) : Reply
        /** The fallback budget is spent; the call closes honestly. */
        data object Exhausted : Reply
    }

    /**
     * One call's mutable state.
     *
     * Held in an inner class rather than in [CallDialogue] so a single instance
     * can serve consecutive calls — a `@Singleton` in `:app` — without one
     * call's turn count leaking into the next.
     */
    private inner class Session(val plan: DialoguePlan) {
        private val startedAt = clock()
        private val transcript = mutableListOf<DialogueTurn>()

        /** Reasoned agent turns delivered so far; 0 during the opening. */
        var turnNumber = 0
            private set

        private var reasonedTurns = 0
        private var reasonerFailures = 0

        var consecutiveFailures = 0
            private set
        private var fallbacksSpoken = 0
        private var fallbackLineIndex = 0
        private var bargeIns = 0

        suspend fun reasonerIsAvailable(): Boolean {
            val began = clock()
            val available = try {
                reasoner.available()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // An availability probe that throws is an unavailable model.
                // It is not worth ending a call over the difference, but it is
                // worth recording which one happened.
                observer.onEvent(
                    DialogueEvent.ReasonerFailed(0, 1, error.messageOrType())
                )
                false
            }
            observer.onEvent(DialogueEvent.ReasonerChecked(available, clock() - began))
            return available
        }

        /** Milliseconds left before the wall-clock budget is spent. */
        fun remainingMs(): Long = plan.budget.maxDurationMs - (clock() - startedAt)

        /**
         * Runs [block] with whatever is left of the call's budget, or returns
         * null if it overran. Cancellation propagates into the block, so a
         * model call still in flight is abandoned rather than left running
         * against a call that has already ended.
         */
        suspend fun <T> withinBudget(block: suspend () -> T): T? {
            val remaining = remainingMs()
            if (remaining <= 0) return null
            return withTimeoutOrNull(remaining) { block() }
        }

        /** Closes the call because the wall-clock budget is spent. */
        suspend fun outOfTime(reasonerOverran: Boolean = false): DialogueOutcome {
            if (reasonerOverran) {
                // Counted, not just logged. A model that ran the call out of
                // time failed that turn as surely as one that threw, and an
                // outcome reporting zero failures for a call the event stream
                // says failed makes the two disagree — which means the run
                // history and any metric built on it are both wrong.
                reasonerFailures += 1
                consecutiveFailures += 1
                observer.onEvent(
                    DialogueEvent.ReasonerFailed(
                        turnNumber,
                        consecutiveFailures,
                        "exceeded the call's remaining time budget",
                    )
                )
            }
            speakClosing(plan.closings.onBudgetReached)
            return finish(StopReason.TIME_BUDGET)
        }

        fun budgetCheck(): Gate = when {
            !transport.isActive() ->
                // Nothing to say and no one to say it to; finish() maps this.
                Gate.Stop(StopReason.CALL_ENDED, "")
            reasonedTurns >= plan.budget.maxTurns ->
                Gate.Stop(StopReason.TURN_BUDGET, plan.closings.onBudgetReached)
            clock() - startedAt >= plan.budget.maxDurationMs ->
                Gate.Stop(StopReason.TIME_BUDGET, plan.closings.onBudgetReached)
            else -> Gate.Continue
        }

        suspend fun listen(): String? {
            val text = transport.awaitCallerReply()?.trim()?.takeIf(String::isNotEmpty)
                ?: return null
            transcript += DialogueTurn(Speaker.CALLER, text)
            observer.onEvent(DialogueEvent.CallerSpoke(turnNumber + 1, text))
            return text
        }

        /**
         * One reasoned turn, with the failure policy applied.
         *
         * A model that returns nothing speakable is a failure, not an empty
         * reply: [SpokenReply.sanitize] returning null means the output was
         * markdown, a stage direction, or whitespace, and speaking any of that
         * is worse than admitting the turn did not work.
         */
        suspend fun reason(callerText: String): Reply {
            turnNumber += 1
            val lastTurn = reasonedTurns + 1 >= plan.budget.maxTurns
            val instructions = plan.systemInstructions +
                if (lastTurn) plan.closingInstruction else ""

            val began = clock()
            val raw = try {
                reasoner.reply(instructions, callerText, history())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return onReasonerFailure(error.messageOrType())
            }

            val text = SpokenReply.sanitize(raw)
                ?: return onReasonerFailure("no speakable text in model output")

            consecutiveFailures = 0
            reasonedTurns += 1
            observer.onEvent(
                DialogueEvent.ReasonerReplied(turnNumber, text, clock() - began)
            )
            return Reply.Spoken(text)
        }

        private fun onReasonerFailure(reason: String): Reply {
            reasonerFailures += 1
            consecutiveFailures += 1
            observer.onEvent(
                DialogueEvent.ReasonerFailed(turnNumber, consecutiveFailures, reason)
            )
            val policy = plan.fallback
            if (policy !is FallbackPolicy.FailSoft ||
                consecutiveFailures > plan.budget.maxConsecutiveReasonerFailures
            ) {
                return Reply.Exhausted
            }
            // Cycled rather than repeated: hearing the same sentence twice is
            // how a caller works out they are talking to something broken.
            val line = policy.lines[fallbackLineIndex % policy.lines.size]
            fallbackLineIndex += 1
            fallbacksSpoken += 1
            observer.onEvent(DialogueEvent.FallbackSpoken(turnNumber, line))
            return Reply.Spoken(line)
        }

        suspend fun speak(text: String, turn: Int): SpeechResult {
            val result = try {
                transport.speak(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                observer.onEvent(
                    DialogueEvent.ReasonerFailed(turn, consecutiveFailures, error.messageOrType())
                )
                SpeechResult.FAILED
            }
            if (result.reachedCaller) {
                transcript += DialogueTurn(
                    Speaker.AGENT,
                    text,
                    interrupted = result == SpeechResult.INTERRUPTED,
                )
            }
            observer.onEvent(DialogueEvent.AgentSpoke(turn, text, result))
            if (result == SpeechResult.INTERRUPTED) {
                bargeIns += 1
                observer.onEvent(DialogueEvent.BargedIn(turn, text))
            }
            return result
        }

        /**
         * Says a closing line, best effort.
         *
         * The call is ending either way, so the result is not checked: if the
         * line does not make it out, the stop reason already says why.
         */
        suspend fun speakClosing(text: String) {
            if (text.isBlank() || !transport.isActive()) return
            speak(text, turnNumber)
        }

        fun finish(stop: StopReason): DialogueOutcome {
            val outcome = DialogueOutcome(
                stop = stop,
                reasonedTurns = reasonedTurns,
                reasonerFailures = reasonerFailures,
                fallbacksSpoken = fallbacksSpoken,
                bargeIns = bargeIns,
                durationMs = clock() - startedAt,
                transcript = transcript.toList(),
            )
            observer.onEvent(DialogueEvent.Closed(outcome))
            return outcome
        }

        /**
         * Recent turns for the model, newest last.
         *
         * Bounded because the window is: Nano's context is small, and an
         * unbounded history means the turn that gets dropped is chosen by the
         * tokenizer rather than by us. Interrupted agent turns go in as spoken —
         * the model must not repeat a sentence the caller cut off, even though
         * the caller never heard the end of it.
         */
        private fun history(): List<Pair<String, String>> =
            transcript
                .dropLast(1) // the utterance being answered is passed separately
                .takeLast(HISTORY_TURNS)
                .map { turn ->
                    val label = if (turn.speaker == Speaker.CALLER) "Caller" else "Agent"
                    label to turn.text
                }
    }

    private fun Throwable.messageOrType(): String =
        message?.takeIf(String::isNotBlank) ?: this::class.simpleName.orEmpty()

    private companion object {
        /**
         * Six turns of context — three exchanges. Enough for the model to know
         * what it already asked, short enough to leave Nano room to answer.
         */
        const val HISTORY_TURNS = 6
    }
}
