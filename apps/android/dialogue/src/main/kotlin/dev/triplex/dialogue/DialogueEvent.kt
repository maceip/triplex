package dev.triplex.dialogue

/**
 * Everything a call did, as it did it.
 *
 * A live phone call is the worst place to debug from: it happens once, in real
 * time, on someone else's schedule, and the only symptom you get is "it went
 * quiet". So the turn loop narrates itself, and the two things that actually
 * matter — how long the model took, and whether it answered at all — are
 * measured rather than inferred.
 *
 * `:app` forwards these to Timber and the run recorder; the conversation tests
 * assert on them, which is why the loop reports through a listener instead of
 * logging directly.
 */
sealed interface DialogueEvent {

    data class Opened(
        val direction: CallDirection,
        val opening: String,
        val budget: DialogueBudget,
    ) : DialogueEvent

    /** The reasoner's availability check, and what it cost to ask. */
    data class ReasonerChecked(val available: Boolean, val latencyMs: Long) : DialogueEvent

    data class CallerSpoke(val turn: Int, val text: String) : DialogueEvent

    /**
     * A reasoned reply came back.
     *
     * [latencyMs] is the number to watch: it is dead air on the call, and the
     * caller hears every millisecond of it.
     */
    data class ReasonerReplied(
        val turn: Int,
        val text: String,
        val latencyMs: Long,
    ) : DialogueEvent

    /**
     * The reasoner did not produce a usable reply.
     *
     * [consecutive] counts failures in a row, which is what the budget is
     * spent against — an isolated failure between two good turns is a different
     * animal from a model that has stopped answering.
     */
    data class ReasonerFailed(
        val turn: Int,
        val consecutive: Int,
        val reason: String,
    ) : DialogueEvent

    /** A content-free holding line was spoken instead of a reasoned reply. */
    data class FallbackSpoken(val turn: Int, val text: String) : DialogueEvent

    data class AgentSpoke(
        val turn: Int,
        val text: String,
        val result: SpeechResult,
    ) : DialogueEvent

    /** The caller talked over the agent; the tail of that turn was not heard. */
    data class BargedIn(val turn: Int, val unheardFrom: String) : DialogueEvent

    data class Closed(val outcome: DialogueOutcome) : DialogueEvent
}

/** Receives [DialogueEvent]s. Must not throw and must not block the call. */
fun interface DialogueObserver {
    fun onEvent(event: DialogueEvent)

    companion object {
        val None: DialogueObserver = DialogueObserver { }
    }
}
