package dev.triplex.ui.agent

import dev.triplex.data.local.db.AgentRunEndReason
import dev.triplex.data.local.db.AgentRunWithTurns
import dev.triplex.data.local.db.AgentTurnEntity
import dev.triplex.data.local.db.AgentTurnSpeaker
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.SystemEvent
import dev.triplex.domain.call.SystemEventKind
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.call.UserReply

/**
 * A recorded call as the agent screens read it.
 *
 * The transcript is a `List<TimelineEntry>` — the same type a live call carries
 * — so the run detail screen renders it with the shared
 * [dev.triplex.ui.call.shared.TranscriptTimeline] rather than a second,
 * database-shaped renderer.
 */
data class AgentRun(
    val id: String,
    val direction: CallDirection,
    val counterpartName: String,
    val counterpartNumber: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val endReason: AgentRunEndReason?,
    val outcomeSummary: String?,
    val timeline: List<TimelineEntry>,
) {
    /**
     * True for a call that is still up — and also for one the recorder never got
     * to close because the process died mid-call. Both are honestly unfinished.
     */
    val unfinished: Boolean get() = endedAtMs == null
}

/**
 * Room's `@Relation` does not order child rows, so the sort by
 * [AgentTurnEntity.seq] happens here, once, for every reader.
 */
fun AgentRunWithTurns.toAgentRun(): AgentRun = AgentRun(
    id = run.id,
    direction = enumValueOrNull<CallDirection>(run.direction) ?: CallDirection.INCOMING,
    counterpartName = run.counterpartName,
    counterpartNumber = run.counterpartNumber,
    startedAtMs = run.startedAtMs,
    endedAtMs = run.endedAtMs,
    endReason = run.endReason?.let { enumValueOrNull<AgentRunEndReason>(it) },
    outcomeSummary = run.outcomeSummary,
    timeline = turns.sortedBy { it.seq }.map(AgentTurnEntity::toTimelineEntry),
)

private fun AgentTurnEntity.toTimelineEntry(): TimelineEntry =
    when (enumValueOrNull<AgentTurnSpeaker>(speaker)) {
        AgentTurnSpeaker.AGENT -> AgentUtterance(text = text, atMs = atMs, complete = final)
        AgentTurnSpeaker.CALLER -> CallerUtterance(text = text, atMs = atMs, final = final)
        AgentTurnSpeaker.USER -> UserReply(text = text, atMs = atMs)
        // A row written by a future build with a speaker this one does not know
        // is shown as a marker rather than dropped or crashed on.
        AgentTurnSpeaker.SYSTEM, null -> SystemEvent(
            kind = enumValueOrNull<SystemEventKind>(text) ?: SystemEventKind.CALL_STARTED,
            atMs = atMs,
        )
    }

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
