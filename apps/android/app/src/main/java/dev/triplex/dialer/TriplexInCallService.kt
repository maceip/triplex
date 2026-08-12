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
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.BuildConfig
import dev.triplex.data.call.TelecomCallController
import dev.triplex.data.call.TelecomCallSnapshot
import dev.triplex.data.call.TelecomCallSource
import dev.triplex.data.telecom.PlaceCallOutcome
import dev.triplex.data.telecom.TelecomAccountsRepository
import dev.triplex.domain.call.AudioEndpointUi
import dev.triplex.domain.call.CallPhase
import javax.inject.Inject

/**
 * The Telecom (SIM) stack's adapter.
 *
 * It translates `android.telecom.Call` into [TelecomCallSnapshot] and performs
 * platform actions as [TelecomCallController]; everything above it reads
 * `CallSessionRepository` instead. The process-wide statics this class used to
 * expose are gone: state now flows through [TelecomCallSource], which the
 * repository can also be handed as a fake.
 */
@AndroidEntryPoint
class TriplexInCallService : InCallService(), TelecomCallController {
    @Inject lateinit var callSource: TelecomCallSource

    @Inject lateinit var notificationController: CallNotificationController

    @Inject lateinit var telecomAccounts: TelecomAccountsRepository

    private val calls = linkedSetOf<Call>()
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private var primaryCall: Call? = null
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var currentEndpoint: CallEndpoint? = null
    private val connectedCalls = mutableSetOf<Call>()
    private val declinedCalls = mutableSetOf<Call>()

    /**
     * Set between dialling the agent leg and conferencing it in.
     *
     * The merge cannot happen at [conferenceInAgent] time: `placeCall` only
     * queues the call with Telecom, and a leg that is still connecting cannot
     * join a conference. [publish] is where the platform tells us it went
     * active, so that is where the merge is attempted.
     */
    private var pendingAgentConference = false

    /** The call the agent leg is being dialled *for*, held while it connects. */
    private var agentConferenceHost: Call? = null

    override fun onCreate() {
        super.onCreate()
        callSource.registerController(this)
        notificationController.start()
    }

    override fun onDestroy() {
        callSource.unregisterController(this)
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
        val missedSnapshot = snapshotOf(call)
        callbacks.remove(call)?.let(call::unregisterCallback)
        calls -= call
        connectedCalls -= call
        declinedCalls -= call
        if (primaryCall == call) primaryCall = calls.lastOrNull()
        if (call == agentConferenceHost) {
            agentConferenceHost = null
            pendingAgentConference = false
        }
        // Order matters: the missed call is recorded before the snapshot is
        // dropped, so the repository sees it while reacting to the session
        // disappearing.
        if (missedCall) callSource.reportMissed(missedSnapshot)
        primaryCall?.let(::publish) ?: callSource.clear()
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        callSource.updateAudio(
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
        if (call.state == Call.STATE_ACTIVE) connectedCalls += call
        if (pendingAgentConference &&
            call.state == Call.STATE_ACTIVE &&
            call != agentConferenceHost
        ) {
            joinAgentConference(call)
        }
        callSource.publish(snapshotOf(call))
    }

    /**
     * Merges the freshly connected agent leg into the call it was dialled for.
     *
     * Runs once: the flag is cleared before anything can fail, so a carrier that
     * refuses the merge leaves the user with two visible calls and a sentence
     * saying so, rather than a retry loop on every state change.
     */
    private fun joinAgentConference(agentLeg: Call) {
        val host = agentConferenceHost
        pendingAgentConference = false
        agentConferenceHost = null

        if (host == null || host !in calls) {
            callSource.updateTransferMessage("The call ended before Triplex could join it")
            return
        }
        val mergeable = host in agentLeg.conferenceableCalls ||
            agentLeg.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
        if (!mergeable) {
            callSource.updateTransferMessage(
                "This carrier will not merge the two calls — Triplex is on a separate line"
            )
            return
        }
        agentLeg.conference(host)
        callSource.updateTransferMessage("Triplex is on this call with you")
    }

    private fun snapshotOf(call: Call): TelecomCallSnapshot {
        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val audio = callAudioState
        return TelecomCallSnapshot(
            id = "telecom:$number:${details.creationTimeMillis}",
            number = number,
            callerDisplayName = details.callerDisplayName?.toString().orEmpty(),
            isIncoming = details.callDirection == Call.Details.DIRECTION_INCOMING,
            phase = phaseOf(call.state),
            statusLabel = statusText(call.state),
            isMuted = audio?.isMuted == true,
            isSpeakerOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentEndpoint?.endpointType == CallEndpoint.TYPE_SPEAKER
            } else {
                audio?.route?.and(CallAudioState.ROUTE_SPEAKER) != 0
            },
            canHold = call.state == Call.STATE_HOLDING || details.can(Call.Details.CAPABILITY_HOLD),
            canRespondViaText = details.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT),
            canDeflectToAgent = BuildConfig.AGENT_TRANSFER_NUMBER.isNotBlank() &&
                details.can(Call.Details.CAPABILITY_SUPPORT_DEFLECT),
            // Conferencing needs no carrier capability up front — it is an
            // ordinary second call — so the only precondition is having a number
            // to dial. `conferenceInAgent` reports what the carrier does with the
            // merge afterwards.
            canConferenceAgent = BuildConfig.AGENT_TRANSFER_NUMBER.isNotBlank(),
            connectedAtMs = details.connectTimeMillis.takeIf { it > 0L },
            otherCallCount = (calls.size - 1).coerceAtLeast(0),
            canMerge = call.conferenceableCalls.isNotEmpty() ||
                details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE),
            canSwap = details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            audioEndpoints = endpointOptions(audio)
        )
    }

    @Suppress("DEPRECATION")
    private fun routeToSpeaker(enabled: Boolean) {
        setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    private fun endpointOptions(audio: CallAudioState?): List<AudioEndpointUi> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && availableEndpoints.isNotEmpty()) {
            return availableEndpoints.map { endpoint ->
                AudioEndpointUi(
                    id = endpoint.identifier.toString(),
                    name = endpoint.endpointName.toString(),
                    selected = endpoint.identifier == currentEndpoint?.identifier
                )
            }
        }

        val supported = audio?.supportedRouteMask ?: 0
        return buildList {
            if (supported and CallAudioState.ROUTE_EARPIECE != 0) {
                add(AudioEndpointUi("legacy:${CallAudioState.ROUTE_EARPIECE}", "Phone", audio?.route == CallAudioState.ROUTE_EARPIECE))
            }
            if (supported and CallAudioState.ROUTE_WIRED_HEADSET != 0) {
                add(AudioEndpointUi("legacy:${CallAudioState.ROUTE_WIRED_HEADSET}", "Wired headset", audio?.route == CallAudioState.ROUTE_WIRED_HEADSET))
            }
            if (supported and CallAudioState.ROUTE_BLUETOOTH != 0) {
                add(AudioEndpointUi("legacy:${CallAudioState.ROUTE_BLUETOOTH}", "Bluetooth", audio?.route == CallAudioState.ROUTE_BLUETOOTH))
            }
            if (supported and CallAudioState.ROUTE_SPEAKER != 0) {
                add(AudioEndpointUi("legacy:${CallAudioState.ROUTE_SPEAKER}", "Speaker", audio?.route == CallAudioState.ROUTE_SPEAKER))
            }
        }
    }

    override fun answer() {
        primaryCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    override fun decline() {
        primaryCall?.let { call ->
            declinedCalls += call
            call.reject(false, null)
        }
    }

    override fun declineWithMessage(message: String) {
        primaryCall?.let { call ->
            declinedCalls += call
            call.reject(true, message)
        }
    }

    override fun disconnect() {
        primaryCall?.disconnect()
    }

    override fun deflectToAgent() {
        val call = primaryCall
        when {
            call == null ->
                callSource.updateTransferMessage("No active incoming call to transfer")

            BuildConfig.AGENT_TRANSFER_NUMBER.isBlank() ->
                callSource.updateTransferMessage("Agent transfer destination is not configured")

            !call.details.can(Call.Details.CAPABILITY_SUPPORT_DEFLECT) ->
                callSource.updateTransferMessage("This carrier does not support call deflection")

            else -> {
                declinedCalls += call
                callSource.updateTransferMessage("Transferring to Triplex agent")
                call.deflect(Uri.fromParts("tel", BuildConfig.AGENT_TRANSFER_NUMBER, null))
            }
        }
    }

    override fun conferenceInAgent() {
        val call = primaryCall
        if (call == null) {
            callSource.updateTransferMessage("There is no call to hand to Triplex")
            return
        }
        if (BuildConfig.AGENT_TRANSFER_NUMBER.isBlank()) {
            callSource.updateTransferMessage("Agent transfer destination is not configured")
            return
        }
        val outcome = telecomAccounts.placeCall(
            BuildConfig.AGENT_TRANSFER_NUMBER,
            telecomAccounts.selectedAccountId.value,
        )
        when (outcome) {
            PlaceCallOutcome.Placed -> {
                agentConferenceHost = call
                pendingAgentConference = true
                callSource.updateTransferMessage("Calling Triplex to join this call")
            }

            PlaceCallOutcome.NeedsDefaultDialerRole -> callSource.updateTransferMessage(
                "Triplex must be your default phone app to add itself to a call"
            )

            PlaceCallOutcome.NeedsCallPermission -> callSource.updateTransferMessage(
                "Phone permission is required to add Triplex to this call"
            )

            is PlaceCallOutcome.Failed -> callSource.updateTransferMessage(outcome.message)
        }
    }

    override fun toggleMute() {
        setMuted(callAudioState?.isMuted != true)
    }

    override fun toggleSpeaker() {
        val speakerOn = callAudioState?.route?.and(CallAudioState.ROUTE_SPEAKER) != 0
        routeToSpeaker(!speakerOn)
    }

    override fun selectAudioEndpoint(endpointId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == endpointId }
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

        endpointId.removePrefix("legacy:").toIntOrNull()?.let(::setAudioRoute)
    }

    override fun toggleHold() {
        primaryCall?.let { call ->
            if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
        }
    }

    override fun holdForAddCall() {
        primaryCall?.let { call ->
            if (call.state == Call.STATE_ACTIVE && call.details.can(Call.Details.CAPABILITY_HOLD)) {
                call.hold()
            }
        }
    }

    override fun switchCall() {
        val current = primaryCall
        val other = calls.firstOrNull { it != current } ?: return
        if (current?.state == Call.STATE_ACTIVE) current.hold()
        if (other.state == Call.STATE_HOLDING) other.unhold()
        primaryCall = other
        publish(other)
    }

    override fun mergeCalls() {
        val current = primaryCall ?: return
        when {
            current.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> current.mergeConference()
            current.conferenceableCalls.isNotEmpty() -> current.conference(current.conferenceableCalls.first())
            else -> calls.firstOrNull { it != current }?.let(current::conference)
        }
    }

    override fun swapConference() {
        primaryCall?.swapConference()
    }

    override fun sendDtmf(digit: Char) {
        primaryCall?.let { call ->
            call.playDtmfTone(digit)
            Handler(Looper.getMainLooper()).postDelayed(call::stopDtmfTone, 180L)
        }
    }

    private companion object {
        fun phaseOf(state: Int): CallPhase = when (state) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_NEW,
            Call.STATE_CONNECTING,
            Call.STATE_DIALING,
            Call.STATE_SELECT_PHONE_ACCOUNT -> CallPhase.DIALING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTING -> CallPhase.ENDING
            Call.STATE_DISCONNECTED -> CallPhase.ENDED
            else -> CallPhase.ACTIVE
        }

        /** Unchanged strings: the in-call header shows these verbatim. */
        fun statusText(state: Int): String = when (state) {
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
