package dev.triplex.dialogue

/** Which way the call was placed. Shapes the brief, the budget, and the exits. */
enum class CallDirection { INBOUND, OUTBOUND }

/** Who said a thing. */
enum class Speaker { AGENT, CALLER }

/**
 * One utterance on the call.
 *
 * @param interrupted true when the caller talked over this agent turn, so the
 *   tail of [text] was synthesized but never heard. Recorded rather than
 *   discarded because the *heard* part is what the caller can be held to have
 *   been told, and the run history has to be able to say which is which. The
 *   sample-accurate boundary lives in the native heard-state
 *   (`RUNTIME_INVARIANTS.md` §3.6); this flag is its product-level shadow.
 */
data class DialogueTurn(
    val speaker: Speaker,
    val text: String,
    val interrupted: Boolean = false,
)

/**
 * How long a conversation may run before the agent closes it out.
 *
 * Every field exists because the failure it prevents is real on a phone call.
 * A model that answers every turn with a question will talk to a patient
 * stranger forever; a caller who sets the phone down leaves the agent listening
 * to a room; a model that has started failing will usually keep failing.
 */
data class DialogueBudget(
    /**
     * Reasoned agent replies allowed after the opening. The opening itself is
     * scripted and does not count against this.
     */
    val maxTurns: Int = 6,

    /**
     * Wall-clock ceiling for the whole dialogue, checked before each new turn
     * so the agent never starts a turn it does not have time to finish.
     */
    val maxDurationMs: Long = 180_000L,

    /**
     * Reasoner failures in a row before the agent stops trying and says so.
     * One transient AICore error should not end a call; a model that has failed
     * twice running is not about to answer the third time.
     */
    val maxConsecutiveReasonerFailures: Int = 2,
) {
    init {
        require(maxTurns >= 1) { "A dialogue with no reasoned turns is not a dialogue" }
        require(maxDurationMs > 0) { "maxDurationMs must be positive" }
        require(maxConsecutiveReasonerFailures >= 1) {
            "At least one reasoner failure must be survivable"
        }
    }
}

/**
 * What the agent does when the reasoner cannot answer.
 *
 * The choice is never "invent something": both policies are content-free. The
 * question is only whether the agent buys one more turn before closing.
 */
sealed interface FallbackPolicy {

    /**
     * Close the call honestly on the first reasoner failure.
     *
     * The right policy when saying the wrong thing costs something — an
     * outbound call where the agent is acting on the user's behalf and a
     * misheard "yes" is a returned television.
     */
    data object FailClosed : FallbackPolicy

    /**
     * Ask the caller to repeat themselves, up to
     * [DialogueBudget.maxConsecutiveReasonerFailures] times, then close.
     *
     * The right policy for inbound screening: a caller who hears "sorry, could
     * you say that once more?" is having a normal phone call, and a one-off
     * AICore hiccup should not hang up on them. [lines] are cycled so a second
     * failure does not repeat the first word for word — nothing sounds more
     * like a broken machine than the same sentence twice.
     */
    data class FailSoft(val lines: List<String>) : FallbackPolicy {
        init {
            require(lines.isNotEmpty()) { "FailSoft needs at least one line to say" }
            require(lines.all(String::isNotBlank)) { "A blank fallback line says nothing" }
        }
    }
}

/**
 * The honest exits. Each one is said out loud before the call ends, so the
 * other party is never left listening to silence.
 */
data class DialogueClosings(
    /** Said when the turn or time budget runs out mid-conversation. */
    val onBudgetReached: String,
    /** Said when the reasoner is gone and the agent will not pretend otherwise. */
    val onReasonerLost: String,
    /** Said when the caller has stopped saying anything. */
    val onCallerSilent: String,
)

/**
 * Everything one call's dialogue needs, assembled before the first word.
 *
 * Built by [DialogueBrief] from user configuration and — outbound — the task,
 * so the strings a call is going to say are decided and inspectable before the
 * SIP leg exists.
 */
data class DialoguePlan(
    val direction: CallDirection,
    /** The scripted first thing the agent says. Never model-generated. */
    val opening: String,
    /** Who the agent is on this call, handed to the reasoner every turn. */
    val systemInstructions: String,
    /**
     * Appended to the instructions on the final reasoned turn so the model
     * lands the conversation instead of opening a new thread.
     */
    val closingInstruction: String,
    val closings: DialogueClosings,
    val budget: DialogueBudget = DialogueBudget(),
    val fallback: FallbackPolicy = FallbackPolicy.FailClosed,
)

/** Why the conversation stopped. */
enum class StopReason {
    /** The caller hung up, or the transport reported the call gone. */
    CALL_ENDED,

    /** [DialogueBudget.maxTurns] reasoned turns were used. */
    TURN_BUDGET,

    /** [DialogueBudget.maxDurationMs] elapsed. */
    TIME_BUDGET,

    /** The caller stopped saying anything. */
    CALLER_SILENT,

    /** The reasoner was unavailable or failed past the budget. */
    REASONER_UNAVAILABLE,

    /** Synthesis or the media path failed; the agent could not be heard. */
    SPEECH_FAILED,
}

/**
 * What the call actually did — the record the run history and the metrics are
 * built from.
 */
data class DialogueOutcome(
    val stop: StopReason,
    /** Reasoned agent turns delivered, excluding opening, fallbacks and closing. */
    val reasonedTurns: Int,
    val reasonerFailures: Int,
    val fallbacksSpoken: Int,
    val bargeIns: Int,
    val durationMs: Long,
    /** Every turn in order, agent and caller, exactly as it happened. */
    val transcript: List<DialogueTurn>,
) {
    /**
     * True when the conversation ended the way it was designed to rather than
     * because something broke. A call that used its whole turn budget did its
     * job; a call that lost the reasoner did not.
     */
    val completedCleanly: Boolean
        get() = stop == StopReason.TURN_BUDGET ||
            stop == StopReason.CALLER_SILENT ||
            stop == StopReason.CALL_ENDED
}
