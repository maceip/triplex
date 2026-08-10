package dev.triplex.data.call

import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.call.UserReply
import dev.triplex.telephony.sip.CallConversationTurn
import dev.triplex.telephony.sip.ConversationSpeaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Plivo/SIP stack knows about the call it is screening.
 *
 * `ScreeningCoordinator` pushes its session and the controller's conversation
 * turns here; this class owns the **only** translation from
 * [CallConversationTurn] into [TimelineEntry], so every surface that shows a
 * call transcript shows the same one.
 *
 * Like [TelecomCallSource] it carries no Android types and is constructible in
 * a JVM test.
 */
@Singleton
class SipCallSource @Inject constructor() {

    /** Overridable so timeline timestamps are deterministic under test. */
    var clock: () -> Long = System::currentTimeMillis

    private val _snapshot = MutableStateFlow<SipCallSnapshot?>(null)
    val snapshot: StateFlow<SipCallSnapshot?> = _snapshot.asStateFlow()

    @Volatile
    private var controller: SipCallController? = null

    private var screening: SipScreeningState? = null
    private var turns: List<CallConversationTurn> = emptyList()

    /**
     * Timestamps are assigned per index and then kept, so a partial turn that
     * is revised in place does not jump to the front of the timeline.
     */
    private val timestamps = mutableMapOf<Int, Long>()
    private val userReplyIndices = mutableSetOf<Int>()
    private val pendingUserReplies = ArrayDeque<String>()
    private var classifiedCount = 0

    fun registerController(controller: SipCallController) {
        this.controller = controller
    }

    fun unregisterController(controller: SipCallController) {
        if (this.controller === controller) this.controller = null
    }

    /**
     * While a debug driver owns this source, the live screening coordinator is
     * ignored. Without this the coordinator's own `null` emissions would erase
     * the scripted call the moment its status message changed.
     */
    @Volatile
    var debugDriving: Boolean = false
        private set

    @Synchronized
    fun publishScreening(state: SipScreeningState?) {
        if (debugDriving) return
        applyScreening(state)
    }

    /**
     * Publishes a scripted snapshot and locks out the live coordinator until
     * [releaseDebugDriving]. Debug builds only — see `DebugCallDriver`.
     */
    @Synchronized
    fun publishDebugScreening(state: SipScreeningState?, turns: List<CallConversationTurn>) {
        debugDriving = true
        // Screening first: a new call id resets the timeline, which would
        // otherwise drop the turns published for it.
        applyScreening(state)
        applyConversation(turns)
    }

    @Synchronized
    fun releaseDebugDriving() {
        debugDriving = false
        applyScreening(null)
    }

    private fun applyScreening(state: SipScreeningState?) {
        if (state == null || state.id != screening?.id) resetTimeline()
        screening = state
        recompute()
    }

    @Synchronized
    fun publishConversation(turns: List<CallConversationTurn>) {
        if (debugDriving) return
        applyConversation(turns)
    }

    private fun applyConversation(turns: List<CallConversationTurn>) {
        if (turns.size < classifiedCount) resetTimeline()
        for (index in classifiedCount until turns.size) {
            val turn = turns[index]
            if (turn.speaker == ConversationSpeaker.TRIPLEX &&
                pendingUserReplies.remove(turn.text)
            ) {
                userReplyIndices += index
            }
        }
        classifiedCount = turns.size
        this.turns = turns
        recompute()
    }

    fun answer() = controller?.answer()

    fun decline() = controller?.decline()

    fun hangUp() = controller?.hangUp()

    fun handOffToAgent(automationId: String?) = controller?.handOffToAgent(automationId)

    fun takeBack() = controller?.takeBack()

    fun speakReply(text: String) {
        recordUserReply(text)
        controller?.speakReply(text)
    }

    @Synchronized
    private fun recordUserReply(text: String) {
        pendingUserReplies += text
    }

    private fun resetTimeline() {
        timestamps.clear()
        userReplyIndices.clear()
        pendingUserReplies.clear()
        classifiedCount = 0
        turns = emptyList()
    }

    private fun recompute() {
        val state = screening
        if (state == null) {
            _snapshot.value = null
            return
        }
        _snapshot.value = SipCallSnapshot(
            id = state.id,
            callerNumber = state.callerNumber,
            statusLabel = statusLabel(state.status),
            statusMessage = state.message,
            agentMode = when {
                state.humanTakeover -> AgentMode.HANDOFF
                state.decision != null -> AgentMode.AUTOMATION
                else -> AgentMode.SCREENING
            },
            decided = state.decision != null,
            timeline = buildTimeline(state.transcript),
            humanTakeover = state.humanTakeover,
        )
    }

    private fun buildTimeline(transcript: String): List<TimelineEntry> {
        if (turns.isEmpty()) {
            // Gateway-side screening sessions carry a transcript but no turns.
            if (transcript.isBlank()) return emptyList()
            return listOf(CallerUtterance(transcript, timestampFor(0), final = false))
        }
        val lastIndex = turns.lastIndex
        return turns.mapIndexed { index, turn ->
            val atMs = timestampFor(index)
            when {
                turn.speaker == ConversationSpeaker.CALLER ->
                    CallerUtterance(turn.text, atMs, final = index < lastIndex)

                index in userReplyIndices -> UserReply(turn.text, atMs)

                else -> AgentUtterance(
                    text = turn.text,
                    atMs = atMs,
                    playbackDurationMs = turn.playbackDurationMs,
                    complete = index < lastIndex
                )
            }
        }
    }

    private fun timestampFor(index: Int): Long = timestamps.getOrPut(index) { clock() }

    /** The screening headline, moved out of the composable that used to build it. */
    private fun statusLabel(status: String): String = when (status) {
        "asking" -> "The caller is answering now"
        "ready", "holding" -> "The caller is on hold"
        "connecting" -> "Connecting you securely"
        else -> status.replaceFirstChar { it.uppercase() }
    }
}

/** The actions the SIP stack can perform on the call it is screening. */
interface SipCallController {
    fun answer()

    fun decline()

    fun hangUp()

    fun handOffToAgent(automationId: String?)

    /**
     * Cuts the agent's current utterance short so the user can steer again.
     *
     * The SIP leg stays up: this stops playback, it does not hang up and it does
     * not attach the device microphone — nothing in this app ever does.
     */
    fun takeBack()

    /** Synthesizes [text] in the user's voice on the live call. */
    fun speakReply(text: String)
}

/** `ScreeningCoordinator`'s session, flattened. */
data class SipScreeningState(
    val id: String,
    val callerNumber: String,
    val status: String,
    val transcript: String,
    /** The automation the user handed the call to, or null while undecided. */
    val decision: String?,
    val message: String,
    /**
     * True once the user pressed Answer on a SIP leg. The SIP audio path still
     * runs agent-only — no device mic or speaker is attached to it — so this
     * flag exists to say so out loud rather than to claim a duplex call.
     */
    val humanTakeover: Boolean = false,
)

data class SipCallSnapshot(
    val id: String,
    val callerNumber: String,
    val statusLabel: String,
    val statusMessage: String,
    val agentMode: AgentMode,
    /** False while the user still owes this call an answer/decline/agent choice. */
    val decided: Boolean,
    val timeline: List<TimelineEntry>,
    /** See [SipScreeningState.humanTakeover]. */
    val humanTakeover: Boolean = false,
)
