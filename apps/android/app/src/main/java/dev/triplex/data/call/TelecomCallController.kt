package dev.triplex.data.call

/**
 * The platform actions a live `InCallService` can perform.
 *
 * Extracted from the statics that used to hang off `TriplexInCallService` so
 * the repository depends on a seam it can fake in a JVM test instead of on an
 * Android service instance.
 */
interface TelecomCallController {
    fun answer()

    fun decline()

    fun declineWithMessage(message: String)

    fun disconnect()

    /** Carrier deflection to the configured agent number. Ringing calls only. */
    fun deflectToAgent()

    /**
     * Brings the agent onto a call that is already connected.
     *
     * Deflection is a ringing-only operation, so the answered SIM call needs the
     * other route the platform allows a dialer: place a second leg to the agent
     * number and conference the two. Progress and failure are reported through
     * `TelecomCallSource.updateTransferMessage`, the same channel
     * [deflectToAgent] uses.
     */
    fun conferenceInAgent()

    fun toggleMute()

    fun toggleSpeaker()

    fun selectAudioEndpoint(endpointId: String)

    fun toggleHold()

    /** Holds the active call so another one can be dialed. */
    fun holdForAddCall()

    fun switchCall()

    fun mergeCalls()

    fun swapConference()

    fun sendDtmf(digit: Char)
}
