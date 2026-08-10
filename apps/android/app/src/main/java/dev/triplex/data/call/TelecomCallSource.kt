package dev.triplex.data.call

import dev.triplex.domain.call.AudioEndpointUi
import dev.triplex.domain.call.CallPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the running `InCallService` knows about the current SIM call.
 *
 * Deliberately free of Android types: `TriplexInCallService` translates
 * `android.telecom.Call` into a [TelecomCallSnapshot] and pushes it here, which
 * keeps both this class and `CallSessionRepositoryImpl` constructible in a
 * plain JVM unit test.
 */
@Singleton
class TelecomCallSource @Inject constructor() {

    private val _snapshot = MutableStateFlow<TelecomCallSnapshot?>(null)
    val snapshot: StateFlow<TelecomCallSnapshot?> = _snapshot.asStateFlow()

    /**
     * Agent-deflection progress and errors.
     *
     * Kept outside the snapshot because it is written before a call exists and
     * survives the snapshot being replaced, exactly as the previous
     * `DialerCallStore.updateTransferMessage` did.
     */
    private val _transferMessage = MutableStateFlow("")
    val transferMessage: StateFlow<String> = _transferMessage.asStateFlow()

    /**
     * The most recent call that rang out without being answered or declined.
     *
     * A value, not an event stream: `onCallRemoved` records it immediately
     * before dropping the snapshot, so the repository can read it while
     * reacting to the session disappearing and label that one `Ended` event
     * correctly instead of racing a second channel.
     */
    private val _lastMissed = MutableStateFlow<TelecomCallSnapshot?>(null)
    val lastMissed: StateFlow<TelecomCallSnapshot?> = _lastMissed.asStateFlow()

    @Volatile
    private var controller: TelecomCallController? = null

    fun registerController(controller: TelecomCallController) {
        this.controller = controller
    }

    fun unregisterController(controller: TelecomCallController) {
        if (this.controller === controller) this.controller = null
    }

    fun publish(snapshot: TelecomCallSnapshot) {
        _snapshot.value = snapshot
    }

    fun clear() {
        _snapshot.value = null
        _transferMessage.value = ""
    }

    fun updateAudio(isMuted: Boolean, isSpeakerOn: Boolean) {
        _snapshot.update { it?.copy(isMuted = isMuted, isSpeakerOn = isSpeakerOn) }
    }

    fun updateTransferMessage(message: String) {
        _transferMessage.value = message
    }

    fun reportMissed(snapshot: TelecomCallSnapshot) {
        _lastMissed.value = snapshot
    }

    fun answer() = controller?.answer()

    fun decline() = controller?.decline()

    fun declineWithMessage(message: String) = controller?.declineWithMessage(message)

    fun disconnect() = controller?.disconnect()

    fun deflectToAgent() = controller?.deflectToAgent()

    fun conferenceInAgent() = controller?.conferenceInAgent()

    fun toggleMute() = controller?.toggleMute()

    fun toggleSpeaker() = controller?.toggleSpeaker()

    fun selectAudioEndpoint(endpointId: String) = controller?.selectAudioEndpoint(endpointId)

    fun toggleHold() = controller?.toggleHold()

    fun holdForAddCall() = controller?.holdForAddCall()

    fun switchCall() = controller?.switchCall()

    fun mergeCalls() = controller?.mergeCalls()

    fun swapConference() = controller?.swapConference()

    fun sendDtmf(digit: Char) = controller?.sendDtmf(digit)
}

/** A single SIM call as the platform currently reports it. */
data class TelecomCallSnapshot(
    /** Stable for the life of one `android.telecom.Call`. */
    val id: String,
    val number: String,
    val callerDisplayName: String,
    val isIncoming: Boolean,
    val phase: CallPhase,
    /** The platform status string the in-call header shows verbatim. */
    val statusLabel: String,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val canHold: Boolean = false,
    val canRespondViaText: Boolean = false,
    val canDeflectToAgent: Boolean = false,
    val connectedAtMs: Long? = null,
    val otherCallCount: Int = 0,
    val canMerge: Boolean = false,
    val canSwap: Boolean = false,
    val audioEndpoints: List<AudioEndpointUi> = emptyList(),
)
