package dev.triplex.dialogue

/**
 * What the agent uses to decide what to say next.
 *
 * The production implementation is `AiCoreCallReasoner` in `:app`, which runs
 * Gemini Nano through AICore on the phone. That class cannot be constructed off
 * a device, so the turn loop depends on this interface instead and the
 * conversation tests drive it with replayed model output.
 *
 * Implementations are expected to be **fail-closed**: a reasoner that cannot
 * produce a genuine reply throws rather than returning filler. Deciding what a
 * call should do about that failure is [CallDialogue]'s job, not the model's —
 * see [FallbackPolicy].
 */
interface CallReasoner {

    /**
     * Whether the model can answer right now.
     *
     * [CallDialogue] calls this once per call, before the first reasoning turn,
     * so a missing model is discovered while the agent is still only saying its
     * scripted opening — not three turns into a conversation it cannot hold.
     * Implementations should be cheap on the second call: on a live phone call
     * a status round-trip per turn is latency the caller hears.
     */
    suspend fun available(): Boolean

    /**
     * One spoken reply, in the agent's own voice.
     *
     * @param systemInstructions who the agent is on this call and what it is
     *   trying to accomplish; built by [DialogueBrief].
     * @param callerText what the other party just said, as transcribed.
     * @param history prior turns as `(speaker label, text)`, oldest first.
     * @throws Exception when no genuine reply can be produced. Callers must not
     *   read a thrown failure as "say nothing" — see [CallDialogue].
     */
    suspend fun reply(
        systemInstructions: String,
        callerText: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String
}
