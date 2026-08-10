package dev.triplex.domain.call

/**
 * One ordered transcript for a call, whoever is speaking (reskin.md §2.3).
 *
 * The agent panel, the screening surface and the (Phase 3) run record all read
 * this list, so the mapping from stack-specific transcript shapes into these
 * entries happens exactly once, in the source that owns the stack.
 */
sealed interface TimelineEntry {
    val atMs: Long
}

/**
 * Something the on-device agent said on the call.
 *
 * @param complete false while this is the newest agent turn and playback may
 *   still be running or the text may still grow.
 */
data class AgentUtterance(
    val text: String,
    override val atMs: Long,
    /**
     * Playback length of the synthesized audio, or 0 when unknown.
     *
     * `TelephonyController` derives it from the PCM it enqueues, so it is the
     * same number the controller waits on before the next turn. Telecom legs
     * carry no agent audio and leave it 0.
     */
    val playbackDurationMs: Long = 0L,
    val complete: Boolean = true,
) : TimelineEntry

/**
 * Recognized caller speech.
 *
 * @param final false while the recognizer may still revise this text in place.
 */
data class CallerUtterance(
    val text: String,
    override val atMs: Long,
    val final: Boolean = true,
) : TimelineEntry

/** A canned reply the user sent through the agent instead of speaking. */
data class UserReply(
    val text: String,
    override val atMs: Long,
) : TimelineEntry

data class SystemEvent(
    val kind: SystemEventKind,
    override val atMs: Long,
) : TimelineEntry

enum class SystemEventKind { CALL_STARTED, AGENT_ATTACHED, CALL_ENDED }
