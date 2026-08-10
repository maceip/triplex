package dev.triplex.dialogue

/**
 * The live call, as far as the turn loop needs to know about it.
 *
 * `TelephonyController` implements this over PJSIP: [speak] streams synthesized
 * PCM onto the SIP leg and [awaitCallerReply] watches the SODA transcript. The
 * loop never learns about call ids, codecs, or epochs — everything that makes a
 * call hard to test stays on the other side of this interface.
 */
interface DialogueTransport {

    /** False once the call this dialogue belongs to is gone. */
    fun isActive(): Boolean

    /**
     * Says [text] to the other party and returns once playback has finished,
     * been interrupted, or failed.
     *
     * Implementations must honor barge-in: when the caller starts talking, stop
     * emitting audio immediately and return [SpeechResult.INTERRUPTED]. Playing
     * to completion over a talking human is the one outcome that is never
     * acceptable.
     */
    suspend fun speak(text: String): SpeechResult

    /**
     * Waits for the other party's next utterance.
     *
     * @return the transcribed text, or null when the caller stayed silent past
     *   the transport's own timeout or the call ended. Null is not an error: a
     *   caller who says nothing is a normal way for a call to go, and
     *   [CallDialogue] closes honestly rather than waiting forever.
     */
    suspend fun awaitCallerReply(): String?
}

/** What actually happened to an utterance the agent tried to say. */
enum class SpeechResult {
    /** The whole utterance reached the other party. */
    DELIVERED,

    /**
     * The caller talked over the agent and playback stopped mid-utterance.
     *
     * Not a failure — it is the pipeline working. The loop treats it as a
     * completed agent turn whose tail was never heard, and listens next.
     */
    INTERRUPTED,

    /** Synthesis or the media path failed; nothing useful was said. */
    FAILED,

    /** The call ended while the agent was speaking. */
    CALL_ENDED,
    ;

    /** True when some audio reached the caller. */
    val reachedCaller: Boolean
        get() = this == DELIVERED || this == INTERRUPTED
}
