package dev.triplex.domain.call

/**
 * Everything a call surface can ask for (reskin.md §2.3).
 *
 * The UI never touches `InCallService` statics or `TelephonyController`
 * directly; it emits one of these and [CallSessionRepository] routes it to the
 * stack that owns the target session. An intent whose matching capability is
 * absent is dropped silently — surfaces gate on [CallCapabilities] to decide
 * what to draw, and a stale tap must never crash a live call.
 */
sealed interface CallIntent {
    data object Answer : CallIntent

    data object Decline : CallIntent

    data class DeclineWithMessage(val message: String) : CallIntent

    data object HangUp : CallIntent

    /**
     * Hand the call to the agent.
     *
     * On the SIP stack the agent takes over media in place; on Telecom it is a
     * carrier deflection to `AGENT_TRANSFER_NUMBER`, because a Telecom call
     * gives a third-party dialer no audio (reskin.md §5).
     */
    data class HandOffToAgent(val automationId: String?) : CallIntent

    /**
     * Stop the agent talking and take the call back.
     *
     * Only the SIP stack can honour it, because only there does Triplex hold the
     * media it would have to release. A deflected or conferenced SIM call is on
     * the carrier's side of the line and the phone cannot recall it — see
     * `CallSessionRepositoryImpl.dispatchTelecom`.
     */
    data object TakeBackFromAgent : CallIntent

    /** Speak text on the caller's behalf. Requires [CallCapabilities.agentHasMedia]. */
    data class SpeakReply(val text: String) : CallIntent

    data object ToggleMute : CallIntent

    /**
     * Not in the reskin.md sketch, but the shipped in-call surface has a
     * Speaker button whose only implementation is the legacy audio-route
     * toggle. [SelectAudioEndpoint] cannot replace it without the UI matching
     * on endpoint names, and dropping it would lose a working control.
     */
    data object ToggleSpeaker : CallIntent

    data object ToggleHold : CallIntent

    data class SelectAudioEndpoint(val endpointId: String) : CallIntent

    data class SendDtmf(val digit: Char) : CallIntent

    /** Puts the current call on hold so the dialpad can place another one. */
    data object AddCall : CallIntent

    data object SwitchCall : CallIntent

    data object MergeCalls : CallIntent

    data object SwapConference : CallIntent
}
