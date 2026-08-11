package dev.triplex.dialer

import dev.triplex.data.call.SipCallController
import dev.triplex.data.call.SipCallSource
import dev.triplex.data.call.SipScreeningState
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.remote.GatewayApi
import dev.triplex.data.remote.ScreeningSession
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.UserRepository
import dev.triplex.telephony.sip.CallStateInfo
import dev.triplex.telephony.sip.TelephonyController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ScreeningDecision(val wireValue: String) {
    ACCEPT("accept"),
    DECLINE("decline"),
    AGENT("agent")
}

/**
 * The Plivo/SIP stack's adapter.
 *
 * It still owns SIP bring-up, the gateway screening poll and the decision
 * wire calls; what it no longer owns is presentation. Its session, message and
 * transcript are pushed into [SipCallSource], and it implements
 * [SipCallController] so `CallSessionRepository` can route intents back to it.
 * The screening notification it used to build itself now comes from
 * [CallNotificationController], the single renderer for both stacks.
 */
@Singleton
class ScreeningCoordinator @Inject constructor(
    private val telephonyController: TelephonyController,
    private val userRepository: UserRepository,
    private val secureStorage: SecureStorage,
    private val gatewayApi: GatewayApi,
    private val sipCallSource: SipCallSource,
    private val agentConfig: AgentConfigRepository,
) : SipCallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _session = MutableStateFlow<ScreeningSession?>(null)
    val session: StateFlow<ScreeningSession?> = _session.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /**
     * Set when the user answers a SIP leg. The SIP call keeps running
     * agent-only — the device mic and speaker are not wired to it — so the UI
     * reads this to say what actually happened instead of implying duplex audio.
     */
    private val _humanTakeover = MutableStateFlow(false)

    override fun answer() = decide(ScreeningDecision.ACCEPT)

    override fun decline() = decide(ScreeningDecision.DECLINE)

    // Screening has no separate hang-up: declining is how a screened call ends.
    override fun hangUp() = decide(ScreeningDecision.DECLINE)

    override fun handOffToAgent(automationId: String?) =
        decide(ScreeningDecision.AGENT, automationId)

    /**
     * Stops the agent mid-sentence and hands the floor back to the user.
     *
     * The leg is not torn down and the device microphone is still not attached
     * to it — taking the call back means the user chooses the next words, which
     * Triplex then speaks, not that the user starts talking into the phone.
     */
    override fun takeBack() {
        telephonyController.interruptPlayback()
        _message.value = "Triplex stopped speaking — send a reply for it to say"
    }

    override fun speakReply(text: String) = telephonyController.speakUserReply(text)

    fun start() {
        if (pollJob?.isActive == true) return
        sipCallSource.registerController(this)
        pollJob = scope.launch {
            launch {
                combine(_session, _message, _humanTakeover) { session, message, takeover ->
                    session?.toSipState(message, takeover)
                }.collect(sipCallSource::publishScreening)
            }
            launch {
                telephonyController.conversationTurns.collect(sipCallSource::publishConversation)
            }
            if (secureStorage.getPlivoDomain() == "phone.plivo.com") {
                userRepository.syncSipCredentials()
            }
            val sipReady = telephonyController.initialize() && telephonyController.registerSip()
            val username = secureStorage.getPlivoUsername()
            if (!sipReady || username.isNullOrBlank()) {
                _message.value = "Secure Plivo SIP registration is not ready"
            } else {
                val domain = secureStorage.getPlivoDomain()
                val endpoint = "sip:$username@$domain"
                userRepository.registerDevice(endpoint, pushToken = null)
                launch { publishReadiness() }
            }
            launch {
                telephonyController.callState.collectLatest(::onCallState)
            }
            launch {
                telephonyController.callTranscript.collectLatest { transcript ->
                    val current = _session.value ?: return@collectLatest
                    _session.value = current.copy(
                        transcript = transcript,
                        updated_at = Instant.now().toString(),
                    )
                }
            }
            launch {
                telephonyController.transcriptionStatus.collectLatest { status ->
                    _message.value = status
                }
            }
            launch { pollGatewayScreening() }
        }
    }

    /**
     * Keeps the gateway's picture of this phone current.
     *
     * Readiness is a claim about *now*, and the gateway stops believing it
     * after a few minutes without a check-in — otherwise a phone that has been
     * switched off since Tuesday still has `ready = true` in the row it wrote
     * before it went away, and a live caller gets routed into nothing. So this
     * re-states the claim on a timer, and re-states it immediately whenever
     * SIP registration changes, so losing registration withdraws the claim
     * rather than waiting for it to expire.
     *
     * Media readiness is the separate, stronger claim: it tells the gateway to
     * connect callers straight to this device instead of screening them, and it
     * is only made when the user has turned the handoff on
     * ([dev.triplex.data.local.AgentInboundConfig.bridgeCallsToPhone]).
     */
    private suspend fun publishReadiness() = coroutineScope {
        // Two reasons to publish, and they need different triggers.
        //
        // A *change* has to go out at once. Registration completing a second
        // after a heartbeat would otherwise leave the gateway believing this
        // phone is unreachable for a minute and a half — long enough for the
        // first outbound call after launch to be refused, and long enough for
        // an inbound caller to be routed to an endpoint that has just dropped.
        // Sampling on a timer cannot do that, however short the timer.
        //
        // The *heartbeat* has to keep going even when nothing changes, because
        // the gateway expires a readiness claim that stops being restated.
        //
        // Both run through this one loop rather than two collectors. The
        // gateway takes the last write, so two publishers racing can land a
        // stale heartbeat *after* a fresh state change and undo it — leaving
        // callers routed to a bridge that has gone away, until the next tick
        // happens to correct it. One publisher, reading the current state at
        // the moment it publishes, cannot invert its own writes.
        val state = combine(
            telephonyController.sipState,
            agentConfig.inbound,
        ) { sipState, inbound ->
            val registered = sipState in READY_SIP_STATES
            Readiness(
                ready = registered,
                mediaReady = registered && inbound.bridgeCallsToPhone,
            )
        }.distinctUntilChanged().stateIn(this)

        var published: Readiness? = null
        while (true) {
            // Read at publish time, never captured earlier: whatever is true
            // now is what the gateway is told.
            val current = state.value
            if (current != published) {
                Timber.i(
                    "Publishing readiness: ready=%s mediaReady=%s",
                    current.ready,
                    current.mediaReady,
                )
            }
            publish(current)
            published = current

            // Wake on the next change, or on the heartbeat, whichever is
            // first. A heartbeat that says the same thing is not logged —
            // ninety seconds of identical lines is noise.
            withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) {
                state.first { it != current }
            }
        }
    }

    private suspend fun publish(readiness: Readiness) {
        val result = userRepository.setDeviceReady(
            ready = readiness.ready,
            mediaReady = readiness.mediaReady,
        )
        if (result is Result.Error) {
            // Not fatal: the next heartbeat restates it. Worth saying once,
            // because a phone that cannot reach the gateway will stop being
            // routed to when its claim expires.
            Timber.w("Could not publish readiness: %s", result.message)
        }
    }

    /** What this phone currently claims it can do. */
    private data class Readiness(val ready: Boolean, val mediaReady: Boolean)

    private suspend fun pollGatewayScreening() {
        while (true) {
            val token = secureStorage.getDeviceToken()
            if (token == null) {
                delay(GATEWAY_POLL_INTERVAL_MS)
                continue
            }
            try {
                val active = gatewayApi.getActiveScreening(token)
                val previous = _session.value
                if (active != null) {
                    _session.value = active
                    _message.value = when (active.status) {
                        "asking" -> "The caller is answering now"
                        "ready", "holding" -> "The caller is waiting for your decision"
                        "agent" -> "Triplex is handling this call"
                        else -> active.status.replaceFirstChar { it.uppercase() }
                    }
                } else if (previous != null && !previous.call_uuid.startsWith("sip-")) {
                    _session.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Live screening refresh failed")
                _message.value = "Reconnecting to live call screening"
            }
            delay(GATEWAY_POLL_INTERVAL_MS)
        }
    }

    private fun onCallState(state: CallStateInfo) {
        when (state) {
            is CallStateInfo.Ringing -> {
                val now = Instant.now().toString()
                val next = ScreeningSession(
                    call_uuid = "sip-${state.callerId}-${System.currentTimeMillis()}",
                    caller_number = state.callerId,
                    called_number = "Triplex SIP",
                    status = "screening",
                    transcript = "",
                    created_at = now,
                    updated_at = now,
                )
                _humanTakeover.value = false
                _session.value = next
            }
            is CallStateInfo.Active -> {
                _session.value = _session.value?.copy(
                    status = "screening",
                    updated_at = Instant.now().toString(),
                )
            }
            CallStateInfo.Idle -> {
                _humanTakeover.value = false
                if (_session.value != null) {
                    _session.value = null
                    _message.value = "Call ended"
                }
            }
            else -> Unit
        }
    }

    fun decide(decision: ScreeningDecision, automationId: String? = null) {
        val current = _session.value ?: return
        scope.launch {
            _message.value = when (decision) {
                ScreeningDecision.ACCEPT -> "Preparing secure phone connection"
                ScreeningDecision.DECLINE -> "Declining call"
                ScreeningDecision.AGENT -> "Starting Book a meeting"
            }
            if (!current.call_uuid.startsWith("sip-")) {
                decideGatewayScreening(current, decision, automationId)
                return@launch
            }
            when (decision) {
                ScreeningDecision.ACCEPT -> {
                    telephonyController.answer()
                    // The SIP leg carries Triplex's synthesized voice and the
                    // caller's audio into transcription. Nothing routes this
                    // phone's microphone or earpiece onto it, so answering does
                    // not put the user on the call — say that, don't imply duplex.
                    _humanTakeover.value = true
                    _message.value = "You are on this call in agent voice only"
                }
                ScreeningDecision.DECLINE -> {
                    telephonyController.hangup()
                    _session.value = null
                    _humanTakeover.value = false
                    _message.value = "Call ended"
                }
                ScreeningDecision.AGENT -> {
                    _session.value = current.copy(
                        decision = automationId,
                        updated_at = Instant.now().toString(),
                    )
                    _message.value = if (
                        automationId != null && telephonyController.startAutomation(automationId)
                    ) {
                        "Automation is speaking and listening on this call"
                    } else {
                        "Automation could not start on this call"
                    }
                }
            }
        }
    }

    private suspend fun decideGatewayScreening(
        current: ScreeningSession,
        decision: ScreeningDecision,
        automationId: String?,
    ) {
        val token = secureStorage.getDeviceToken()
        if (token == null) {
            _message.value = "This phone is not connected to Triplex"
            return
        }
        try {
            val updated = gatewayApi.decideScreening(
                current.call_uuid,
                decision.wireValue,
                automationId,
                token,
            )
            _session.value = updated
            when (decision) {
                ScreeningDecision.ACCEPT -> _message.value = "Connecting the call"
                ScreeningDecision.DECLINE -> {
                    _session.value = null
                    _message.value = "Call ended"
                }
                ScreeningDecision.AGENT -> _message.value = "Triplex is handling this call"
            }
        } catch (error: Exception) {
            Timber.e(error, "Screening decision failed")
            _message.value = "Could not update the live call. Try again."
        }
    }

    private fun ScreeningSession.toSipState(
        message: String,
        humanTakeover: Boolean,
    ) = SipScreeningState(
        id = call_uuid,
        callerNumber = caller_number,
        status = status,
        transcript = transcript,
        decision = decision,
        message = message,
        humanTakeover = humanTakeover && call_uuid.startsWith("sip-"),
    )

    private companion object {
        const val GATEWAY_POLL_INTERVAL_MS = 750L

        /**
         * Comfortably inside the gateway's `DEVICE_HEARTBEAT_TTL_SECONDS`
         * (300 s by default), so a single missed check-in — a tunnel, a
         * backgrounded app — does not make the phone unreachable.
         *
         * This paces the *keep-alive* only. Changes do not wait for it.
         */
        const val HEARTBEAT_INTERVAL_MS = 90_000L

        /** SIP states in which this phone would actually answer an INVITE. */
        val READY_SIP_STATES = setOf(
            TelephonyController.SipState.READY,
            TelephonyController.SipState.IN_CALL,
        )
    }
}
