package dev.triplex.data.call

import dev.triplex.dialer.DialerContact
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.CallCapabilities
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallParty
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import dev.triplex.domain.call.CallStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Merges the Telecom and SIP stacks into one call model and routes intents back.
 *
 * Constructed by hand rather than by Hilt (see `di/CallModule`) because every
 * collaborator is an interface or an Android-free source and the scope is a
 * parameter — that is what lets a JVM test drive the whole merge with fakes and
 * `runTest`'s scheduler.
 */
class CallSessionRepositoryImpl(
    private val telecomSource: TelecomCallSource,
    private val sipSource: SipCallSource,
    private val identityResolver: CallerIdentityResolver,
    private val phoneNumberFormatter: PhoneNumberFormatter,
    private val scope: CoroutineScope,
) : CallSessionRepository {

    private val _events = MutableSharedFlow<CallLifecycleEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallLifecycleEvent> = _events.asSharedFlow()

    override val sessions: StateFlow<List<CallSession>> = combine(
        telecomSource.snapshot,
        telecomSource.transferMessage,
        sipSource.snapshot,
        identityResolver.contacts,
    ) { telecom, transferMessage, sip, contacts ->
        buildList {
            // Telecom first: a SIM call owns the device's audio and the
            // platform ringer, so with both stacks live it is the call the
            // user is actually being asked about.
            telecom?.let { add(it.toSession(transferMessage, contacts)) }
            sip?.let { add(it.toSession(contacts)) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val session: StateFlow<CallSession?> = sessions
        .map { it.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch { trackLifecycle() }
    }

    private suspend fun trackLifecycle() {
        var previous: CallSession? = null
        session.collect { current ->
            val prior = previous
            if (prior != null && prior.id != current?.id) {
                _events.tryEmit(CallLifecycleEvent.Ended(prior, missed = wasMissed(prior)))
            }
            if (current != null && prior?.id != current.id) {
                identityResolver.refresh()
                _events.tryEmit(CallLifecycleEvent.Started(current))
            }
            if (current != null &&
                current.connectedAtMs != null &&
                (prior?.id != current.id || prior.connectedAtMs == null)
            ) {
                _events.tryEmit(CallLifecycleEvent.Connected(current))
            }
            previous = current
        }
    }

    private fun wasMissed(session: CallSession): Boolean =
        session.stack == CallStack.TELECOM && telecomSource.lastMissed.value?.id == session.id

    override fun dispatch(intent: CallIntent, sessionId: String?) {
        val target = sessionId
            ?.let { id -> sessions.value.firstOrNull { it.id == id } }
            ?: sessions.value.firstOrNull()
            ?: return

        when (target.stack) {
            CallStack.TELECOM -> dispatchTelecom(intent, target)
            CallStack.SIP -> dispatchSip(intent, target)
        }
    }

    private fun dispatchTelecom(intent: CallIntent, session: CallSession) {
        val capabilities = session.capabilities
        when (intent) {
            CallIntent.Answer -> if (capabilities.canAnswer) telecomSource.answer()

            CallIntent.Decline -> if (capabilities.canDecline) telecomSource.decline()

            is CallIntent.DeclineWithMessage ->
                if (capabilities.canTextReply) telecomSource.declineWithMessage(intent.message)

            CallIntent.HangUp -> telecomSource.disconnect()

            // Two different platform operations wear one intent, because the
            // user is asking for one thing ("put Triplex on this") and the phase
            // decides what that costs. Ringing: carrier deflection, which hands
            // the call away untouched. Connected: a second leg to the agent
            // number, conferenced in — deflection is not available once a call
            // has been answered.
            //
            // Neither branch is gated on canDeflectToAgent: the controller
            // answers an impossible hand-off with a user-facing reason ("This
            // carrier does not support call deflection"), and swallowing the
            // intent would swallow that message too. The capability still drives
            // how the surface renders the result.
            is CallIntent.HandOffToAgent ->
                if (session.phase == CallPhase.RINGING) {
                    telecomSource.deflectToAgent()
                } else {
                    telecomSource.conferenceInAgent()
                }

            // Once a SIM call has been deflected or conferenced, the agent leg
            // belongs to the carrier. There is no recall to dispatch.
            CallIntent.TakeBackFromAgent -> Unit

            // A third-party dialer gets no media on a SIM leg (reskin.md §5).
            is CallIntent.SpeakReply -> Unit

            CallIntent.ToggleMute -> if (capabilities.canMute) telecomSource.toggleMute()

            CallIntent.ToggleSpeaker -> if (capabilities.canMute) telecomSource.toggleSpeaker()

            CallIntent.ToggleHold -> if (capabilities.canHold) telecomSource.toggleHold()

            is CallIntent.SelectAudioEndpoint ->
                if (capabilities.audioEndpoints.any { it.id == intent.endpointId }) {
                    telecomSource.selectAudioEndpoint(intent.endpointId)
                }

            is CallIntent.SendDtmf ->
                if (capabilities.canSendDtmf) telecomSource.sendDtmf(intent.digit)

            CallIntent.AddCall -> if (capabilities.canAddCall) telecomSource.holdForAddCall()

            CallIntent.SwitchCall -> if (capabilities.canSwitch) telecomSource.switchCall()

            CallIntent.MergeCalls -> if (capabilities.canMerge) telecomSource.mergeCalls()

            CallIntent.SwapConference -> if (capabilities.canSwap) telecomSource.swapConference()
        }
    }

    private fun dispatchSip(intent: CallIntent, session: CallSession) {
        val capabilities = session.capabilities
        when (intent) {
            CallIntent.Answer -> if (capabilities.canAnswer) sipSource.answer()

            CallIntent.Decline -> if (capabilities.canDecline) sipSource.decline()

            CallIntent.HangUp -> sipSource.hangUp()

            is CallIntent.HandOffToAgent ->
                if (capabilities.agentHasMedia) sipSource.handOffToAgent(intent.automationId)

            CallIntent.TakeBackFromAgent ->
                if (capabilities.agentHasMedia) sipSource.takeBack()

            is CallIntent.SpeakReply ->
                if (capabilities.agentHasMedia) sipSource.speakReply(intent.text)

            // The SIP leg has no SIM-side controls: no text reply, no hold, no
            // DTMF, no conference, and audio routing belongs to the endpoint.
            else -> Unit
        }
    }

    private fun TelecomCallSnapshot.toSession(
        transferMessage: String,
        contacts: List<DialerContact>,
    ): CallSession {
        val contact = contacts.firstOrNull { sameNumber(it.number, number) }
        return CallSession(
            id = id,
            stack = CallStack.TELECOM,
            direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING,
            party = CallParty(
                number = number,
                displayName = contact?.name?.takeIf(String::isNotBlank)
                    ?: callerDisplayName.ifBlank { number },
                contactId = contact?.id,
            ),
            phase = phase,
            agentMode = AgentMode.NONE,
            connectedAtMs = connectedAtMs?.takeIf { it > 0L },
            capabilities = CallCapabilities(
                canAnswer = phase == CallPhase.RINGING,
                canDecline = phase == CallPhase.RINGING,
                canTextReply = canRespondViaText && phase == CallPhase.RINGING,
                canHold = canHold,
                canMute = phase != CallPhase.RINGING,
                canSendDtmf = phase != CallPhase.RINGING,
                canAddCall = phase != CallPhase.RINGING,
                canSwitch = otherCallCount > 0,
                canMerge = canMerge,
                canSwap = canSwap,
                canDeflectToAgent = canDeflectToAgent,
                canConferenceAgent = canConferenceAgent,
                agentHasMedia = false,
                audioEndpoints = audioEndpoints,
            ),
            timeline = emptyList(),
            otherCallCount = otherCallCount,
            statusLabel = statusLabel,
            statusMessage = transferMessage,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
        )
    }

    private fun SipCallSnapshot.toSession(contacts: List<DialerContact>): CallSession {
        val contact = contacts.firstOrNull { sameNumber(it.number, callerNumber) }
        return CallSession(
            id = id,
            stack = CallStack.SIP,
            direction = CallDirection.INCOMING,
            party = CallParty(
                number = callerNumber,
                // Formatting is applied on this stack only: the SIM surface has
                // always shown the raw handle, while the screening surface
                // receives a gateway-supplied caller id.
                displayName = contact?.name?.takeIf(String::isNotBlank)
                    ?: phoneNumberFormatter.format(callerNumber).ifBlank { "Unknown caller" },
                contactId = contact?.id,
            ),
            // A screened leg stops being an incoming question the moment the
            // user has answered it or handed it to an automation: from then on
            // it is a running call, and the in-call surface — which owns the
            // agent panel, the transcript and the take-back control — is where
            // it belongs (reskin.md §3.4).
            phase = if (humanTakeover || decided) CallPhase.ACTIVE else CallPhase.SCREENING,
            agentMode = agentMode,
            // The SIP stack reports no connect time, and inventing one would put
            // a running clock on a call whose duration we do not know.
            connectedAtMs = null,
            capabilities = CallCapabilities(
                // Answering a SIP leg is a one-shot: there is no second
                // takeover to offer once the user has taken it.
                canAnswer = !decided && !humanTakeover,
                canDecline = !decided,
                canDeflectToAgent = !decided,
                agentHasMedia = true,
            ),
            timeline = timeline,
            otherCallCount = 0,
            statusLabel = statusLabel,
            statusMessage = statusMessage,
        )
    }

    /**
     * Compares dial strings the way a person would: by the digits that
     * identify the line, ignoring country/trunk prefixes and formatting.
     */
    private fun sameNumber(left: String, right: String): Boolean {
        val a = left.filter { it.isDigit() }
        val b = right.filter { it.isDigit() }
        if (a.isEmpty() || b.isEmpty()) return false
        return if (a.length >= SIGNIFICANT_DIGITS && b.length >= SIGNIFICANT_DIGITS) {
            a.takeLast(SIGNIFICANT_DIGITS) == b.takeLast(SIGNIFICANT_DIGITS)
        } else {
            a == b
        }
    }

    private companion object {
        const val SIGNIFICANT_DIGITS = 7
    }
}
