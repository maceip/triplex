package dev.triplex.dialer

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import dev.triplex.BuildConfig

data class DialerAudioEndpoint(
    val id: String,
    val name: String,
    val selected: Boolean
)

data class DialerCallUiState(
    val hasCall: Boolean = false,
    val displayName: String = "",
    val number: String = "",
    val status: String = "Idle",
    val isIncoming: Boolean = false,
    val isRinging: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isOnHold: Boolean = false,
    val canHold: Boolean = false,
    val canRespondViaText: Boolean = false,
    val canTransferToAgent: Boolean = false,
    val transferMessage: String = "",
    val connectTimeMillis: Long = 0L,
    val otherCallCount: Int = 0,
    val canMergeCalls: Boolean = false,
    val canSwapConference: Boolean = false,
    val audioEndpoints: List<DialerAudioEndpoint> = emptyList()
)

object DialerCallStore {
    private val mutableState = MutableStateFlow(DialerCallUiState())
    val state: StateFlow<DialerCallUiState> = mutableState.asStateFlow()

    internal fun publish(value: DialerCallUiState) {
        mutableState.value = value
    }

    internal fun updateAudio(isMuted: Boolean, isSpeakerOn: Boolean) {
        mutableState.update { it.copy(isMuted = isMuted, isSpeakerOn = isSpeakerOn) }
    }

    internal fun updateTransferMessage(message: String) {
        mutableState.update { it.copy(transferMessage = message) }
    }
}

class TriplexInCallService : InCallService() {
    private val calls = linkedSetOf<Call>()
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private var primaryCall: Call? = null
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var currentEndpoint: CallEndpoint? = null
    private val connectedCalls = mutableSetOf<Call>()
    private val declinedCalls = mutableSetOf<Call>()

    override fun onCreate() {
        super.onCreate()
        activeService = this
        DialerCallNotificationManager.createChannel(this)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls += call
        primaryCall = call
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                publish(call)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                publish(call)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        publish(call)
    }

    override fun onCallRemoved(call: Call) {
        val missedCall = call.details.callDirection == Call.Details.DIRECTION_INCOMING &&
            call !in connectedCalls && call !in declinedCalls
        val missedState = DialerCallStore.state.value
        callbacks.remove(call)?.let(call::unregisterCallback)
        calls -= call
        connectedCalls -= call
        declinedCalls -= call
        if (primaryCall == call) primaryCall = calls.lastOrNull()
        primaryCall?.let(::publish) ?: run {
            DialerCallStore.publish(DialerCallUiState())
            DialerCallNotificationManager.cancel(this)
        }
        if (missedCall) DialerCallNotificationManager.showMissed(this, missedState)
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        DialerCallStore.updateAudio(
            isMuted = audioState.isMuted,
            isSpeakerOn = audioState.route and CallAudioState.ROUTE_SPEAKER != 0
        )
        primaryCall?.let(::publish)
    }

    override fun onAvailableCallEndpointsChanged(availableCallEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableCallEndpoints)
        availableEndpoints = availableCallEndpoints
        primaryCall?.let(::publish)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        currentEndpoint = callEndpoint
        primaryCall?.let(::publish)
    }

    private fun publish(call: Call) {
        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val displayName = details.callerDisplayName?.toString().orEmpty()
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val audio = callAudioState
        if (call.state == Call.STATE_ACTIVE) connectedCalls += call
        val state = DialerCallUiState(
            hasCall = true,
            displayName = displayName,
            number = number,
            status = statusText(call.state),
            isIncoming = incoming,
            isRinging = call.state == Call.STATE_RINGING,
            isMuted = audio?.isMuted == true,
            isSpeakerOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentEndpoint?.endpointType == CallEndpoint.TYPE_SPEAKER
            } else {
                audio?.route?.and(CallAudioState.ROUTE_SPEAKER) != 0
            },
            isOnHold = call.state == Call.STATE_HOLDING,
            canHold = call.state == Call.STATE_HOLDING || details.can(Call.Details.CAPABILITY_HOLD),
            canRespondViaText = details.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT),
            canTransferToAgent = BuildConfig.AGENT_TRANSFER_NUMBER.isNotBlank() &&
                details.can(Call.Details.CAPABILITY_SUPPORT_DEFLECT),
            transferMessage = DialerCallStore.state.value.transferMessage,
            connectTimeMillis = details.connectTimeMillis,
            otherCallCount = (calls.size - 1).coerceAtLeast(0),
            canMergeCalls = call.conferenceableCalls.isNotEmpty() ||
                details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE),
            canSwapConference = details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            audioEndpoints = endpointOptions(audio)
        )
        DialerCallStore.publish(state)

        when (call.state) {
            Call.STATE_RINGING -> DialerCallNotificationManager.showIncoming(this, state)
            Call.STATE_DISCONNECTED -> DialerCallNotificationManager.cancel(this)
            else -> DialerCallNotificationManager.showOngoing(this, state)
        }
    }

    @Suppress("DEPRECATION")
    private fun routeToSpeaker(enabled: Boolean) {
        setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    private fun endpointOptions(audio: CallAudioState?): List<DialerAudioEndpoint> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && availableEndpoints.isNotEmpty()) {
            return availableEndpoints.map { endpoint ->
                DialerAudioEndpoint(
                    id = endpoint.identifier.toString(),
                    name = endpoint.endpointName.toString(),
                    selected = endpoint.identifier == currentEndpoint?.identifier
                )
            }
        }

        val supported = audio?.supportedRouteMask ?: 0
        return buildList {
            if (supported and CallAudioState.ROUTE_EARPIECE != 0) {
                add(DialerAudioEndpoint("legacy:${CallAudioState.ROUTE_EARPIECE}", "Phone", audio?.route == CallAudioState.ROUTE_EARPIECE))
            }
            if (supported and CallAudioState.ROUTE_WIRED_HEADSET != 0) {
                add(DialerAudioEndpoint("legacy:${CallAudioState.ROUTE_WIRED_HEADSET}", "Wired headset", audio?.route == CallAudioState.ROUTE_WIRED_HEADSET))
            }
            if (supported and CallAudioState.ROUTE_BLUETOOTH != 0) {
                add(DialerAudioEndpoint("legacy:${CallAudioState.ROUTE_BLUETOOTH}", "Bluetooth", audio?.route == CallAudioState.ROUTE_BLUETOOTH))
            }
            if (supported and CallAudioState.ROUTE_SPEAKER != 0) {
                add(DialerAudioEndpoint("legacy:${CallAudioState.ROUTE_SPEAKER}", "Speaker", audio?.route == CallAudioState.ROUTE_SPEAKER))
            }
        }
    }

    private fun selectEndpoint(id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == id }
            if (endpoint != null) {
                requestCallEndpointChange(
                    endpoint,
                    mainExecutor,
                    object : OutcomeReceiver<Void, CallEndpointException> {
                        override fun onResult(result: Void?) = Unit
                        override fun onError(error: CallEndpointException) = Unit
                    }
                )
                return
            }
        }

        id.removePrefix("legacy:").toIntOrNull()?.let(::setAudioRoute)
    }

    companion object {
        @Volatile
        private var activeService: TriplexInCallService? = null

        fun answerCurrent() {
            activeService?.primaryCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun declineCurrent() {
            activeService?.let { service ->
                service.primaryCall?.let { call ->
                    service.declinedCalls += call
                    call.reject(false, null)
                }
            }
        }

        fun declineWithMessage() {
            activeService?.let { service ->
                service.primaryCall?.let { call ->
                    service.declinedCalls += call
                    call.reject(true, "Can't talk right now.")
                }
            }
        }

        fun transferCurrentToAgent() {
            val service = activeService
            val call = service?.primaryCall
            when {
                service == null || call == null -> {
                    DialerCallStore.updateTransferMessage("No active incoming call to transfer")
                }
                BuildConfig.AGENT_TRANSFER_NUMBER.isBlank() -> {
                    DialerCallStore.updateTransferMessage("Agent transfer destination is not configured")
                }
                !call.details.can(Call.Details.CAPABILITY_SUPPORT_DEFLECT) -> {
                    DialerCallStore.updateTransferMessage("This carrier does not support call deflection")
                }
                else -> {
                    service.declinedCalls += call
                    DialerCallStore.updateTransferMessage("Transferring to Triplex agent")
                    call.deflect(Uri.fromParts("tel", BuildConfig.AGENT_TRANSFER_NUMBER, null))
                }
            }
        }

        fun disconnectCurrent() {
            activeService?.primaryCall?.disconnect()
        }

        fun toggleMute() {
            activeService?.let { service ->
                service.setMuted(service.callAudioState?.isMuted != true)
            }
        }

        fun toggleSpeaker() {
            activeService?.let { service ->
                val speakerOn = service.callAudioState?.route?.and(CallAudioState.ROUTE_SPEAKER) != 0
                service.routeToSpeaker(!speakerOn)
            }
        }

        fun selectAudioEndpoint(id: String) {
            activeService?.selectEndpoint(id)
        }

        fun toggleHold() {
            activeService?.primaryCall?.let { call ->
                if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
            }
        }

        fun holdForAddCall() {
            activeService?.primaryCall?.let { call ->
                if (call.state == Call.STATE_ACTIVE && call.details.can(Call.Details.CAPABILITY_HOLD)) {
                    call.hold()
                }
            }
        }

        fun switchCall() {
            activeService?.let { service ->
                val current = service.primaryCall
                val other = service.calls.firstOrNull { it != current } ?: return
                if (current?.state == Call.STATE_ACTIVE) current.hold()
                if (other.state == Call.STATE_HOLDING) other.unhold()
                service.primaryCall = other
                service.publish(other)
            }
        }

        fun mergeCalls() {
            activeService?.let { service ->
                val current = service.primaryCall ?: return
                when {
                    current.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> current.mergeConference()
                    current.conferenceableCalls.isNotEmpty() -> current.conference(current.conferenceableCalls.first())
                    else -> service.calls.firstOrNull { it != current }?.let(current::conference)
                }
            }
        }

        fun swapConference() {
            activeService?.primaryCall?.swapConference()
        }

        fun sendDtmf(digit: Char) {
            activeService?.primaryCall?.let { call ->
                call.playDtmfTone(digit)
                Handler(Looper.getMainLooper()).postDelayed(call::stopDtmfTone, 180L)
            }
        }

        private fun statusText(state: Int): String = when (state) {
            Call.STATE_NEW -> "New call"
            Call.STATE_CONNECTING -> "Connecting"
            Call.STATE_DIALING -> "Dialing"
            Call.STATE_RINGING -> "Ringing"
            Call.STATE_ACTIVE -> "Connected"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTED -> "Call ended"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
            Call.STATE_DISCONNECTING -> "Ending call"
            else -> "Call in progress"
        }
    }
}
