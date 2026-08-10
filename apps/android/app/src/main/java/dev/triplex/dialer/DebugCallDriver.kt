package dev.triplex.dialer

import dev.triplex.data.call.SipCallController
import dev.triplex.data.call.SipCallSource
import dev.triplex.data.call.SipScreeningState
import dev.triplex.data.call.TelecomCallSnapshot
import dev.triplex.data.call.TelecomCallSource
import dev.triplex.domain.call.CallPhase
import dev.triplex.telephony.sip.CallConversationTurn
import dev.triplex.telephony.sip.ConversationSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fabricates calls so the incoming sheet can be exercised without a live leg.
 *
 * Both stacks are reachable: a SIM leg, which has no agent media and therefore
 * shows the capability-gated fallbacks, and a screened SIP leg, which shows the
 * transcript, the reply chips and the honest post-answer state. Nothing here
 * fakes UI — it publishes into the same sources the real stacks publish into, so
 * whatever the sheet does with a scripted call is what it will do with a real
 * one.
 *
 * Guarded by `BuildConfig.DEBUG` at its call site in [DialerActivity].
 */
@Singleton
class DebugCallDriver @Inject constructor(
    private val sipCallSource: SipCallSource,
    private val telecomCallSource: TelecomCallSource,
    private val screeningCoordinator: ScreeningCoordinator,
) : SipCallController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scriptJob: Job? = null

    private var turns: List<CallConversationTurn> = emptyList()
    private var state: SipScreeningState? = null

    /** Publishes a ringing SIM call: no agent media, carrier-gated controls. */
    fun startTelecomRinging() {
        // Publishing the snapshot is the whole fake: the session, the incoming
        // sheet and the notification all fall out of the repository.
        telecomCallSource.publish(
            TelecomCallSnapshot(
                id = "telecom:+13185550100:0",
                number = "+1 318 555 0100",
                callerDisplayName = "Triplex test caller",
                isIncoming = true,
                phase = CallPhase.RINGING,
                statusLabel = "Ringing",
            )
        )
    }

    /**
     * Publishes a screened SIP call and plays a short scripted conversation.
     *
     * Takes over [SipCallSource] while it runs so the live coordinator's own
     * emissions cannot erase the script mid-demo.
     */
    fun startSipScreening() {
        scriptJob?.cancel()
        sipCallSource.registerController(this)
        turns = emptyList()
        state = SipScreeningState(
            id = "sip-debug-${System.currentTimeMillis()}",
            callerNumber = "+13185550142",
            status = "asking",
            transcript = "",
            decision = null,
            message = "Triplex is screening this call",
        )
        publish()

        scriptJob = scope.launch {
            delay(SCRIPT_STEP_MS)
            append(ConversationSpeaker.TRIPLEX, AGENT_GREETING, playbackDurationMs = 4_200L)
            delay(SCRIPT_STEP_MS)
            append(ConversationSpeaker.CALLER, "Hi, this is Dana from the clinic.")
            delay(SCRIPT_STEP_MS)
            append(
                ConversationSpeaker.CALLER,
                "Hi, this is Dana from the clinic, calling about tomorrow's appointment.",
            )
        }
    }

    override fun answer() {
        // The same claim the live coordinator makes, for the same reason: the
        // SIP leg carries the agent's voice, not this phone's microphone.
        update {
            it.copy(
                status = "connecting",
                message = "You are on this call in agent voice only",
                humanTakeover = true,
            )
        }
    }

    override fun decline() = stop()

    override fun hangUp() = stop()

    override fun handOffToAgent(automationId: String?) {
        update {
            it.copy(
                status = "agent",
                decision = automationId ?: "book_zoom",
                message = "Automation is speaking and listening on this call",
            )
        }
    }

    override fun takeBack() {
        update {
            it.copy(
                status = "asking",
                decision = null,
                message = "Triplex stopped speaking — send a reply for it to say",
            )
        }
    }

    override fun speakReply(text: String) {
        // Published as a TRIPLEX turn on purpose: the agent is what speaks a
        // user reply on a real call, and SipCallSource is what re-attributes it
        // to the user. Faking a USER turn here would skip the code under test.
        append(ConversationSpeaker.TRIPLEX, text, playbackDurationMs = 2_500L)
    }

    private fun stop() {
        scriptJob?.cancel()
        scriptJob = null
        turns = emptyList()
        state = null
        sipCallSource.releaseDebugDriving()
        // Hand the source back to the live coordinator, which registered before
        // this driver took over.
        sipCallSource.registerController(screeningCoordinator)
    }

    @Synchronized
    private fun append(
        speaker: ConversationSpeaker,
        text: String,
        playbackDurationMs: Long = 0L,
    ) {
        if (state == null) return
        // A caller turn that extends the previous one replaces it, the way a
        // live partial transcript is revised in place.
        val last = turns.lastOrNull()
        turns = if (
            speaker == ConversationSpeaker.CALLER &&
            last?.speaker == ConversationSpeaker.CALLER &&
            text.startsWith(last.text.dropLast(1))
        ) {
            turns.dropLast(1) + CallConversationTurn(speaker, text, playbackDurationMs)
        } else {
            turns + CallConversationTurn(speaker, text, playbackDurationMs)
        }
        publish()
    }

    @Synchronized
    private fun update(transform: (SipScreeningState) -> SipScreeningState) {
        val current = state ?: return
        state = transform(current)
        publish()
    }

    private fun publish() {
        sipCallSource.publishDebugScreening(state, turns)
    }

    private companion object {
        const val SCRIPT_STEP_MS = 1_800L
        const val AGENT_GREETING =
            "Hi. This is the Triplex screening assistant. " +
                "Please say your name and why you are calling."
    }
}
