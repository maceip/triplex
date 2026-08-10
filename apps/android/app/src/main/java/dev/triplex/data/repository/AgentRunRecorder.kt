package dev.triplex.data.repository

import dev.triplex.data.local.db.AgentCallRunEntity
import dev.triplex.data.local.db.AgentRunDao
import dev.triplex.data.local.db.AgentRunEndReason
import dev.triplex.data.local.db.AgentTurnEntity
import dev.triplex.data.local.db.AgentTurnSpeaker
import dev.triplex.di.ApplicationScope
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.SystemEvent
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.call.UserReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes every agent-handled call into the local run history (reskin.md §2.4).
 *
 * Attaches to [CallSessionRepository] — the seam Phase 1 left for exactly this —
 * rather than polling call state or reaching into either telephony stack. It
 * therefore records SIM calls and SIP calls through the same code path, and it
 * is testable on the JVM with a fake repository and a fake DAO.
 *
 * ## What becomes a run
 *
 * A row appears the moment a session reports [AgentMode] other than
 * [AgentMode.NONE], not when the call starts. A call the user simply takes is
 * not the agent's business and leaves no transcript behind.
 *
 * ## Crash mid-call
 *
 * Every write is an idempotent upsert keyed by primary key, and nothing is
 * buffered until the end of the call: turns land as the timeline grows. If the
 * process dies mid-call the run row and the turns written so far survive, with
 * `endedAtMs` and `endReason` left null — a visibly unfinished run, which is
 * the truth, rather than a lost or a falsely-completed one.
 *
 * ## Ordering
 *
 * `sessions` and `events` are merged into one coroutine so the bookkeeping maps
 * below need no locking. The two still race in *content*: a session can drop
 * out of `sessions` before its [CallLifecycleEvent.Ended] arrives (and a
 * non-primary SIP session never gets an `Ended` at all, since the repository
 * derives lifecycle from the primary session). A disappearance therefore closes
 * the run as [AgentRunEndReason.UNKNOWN], and a lifecycle event that lands
 * afterwards is allowed to upgrade that one value. No other reason is ever
 * overwritten.
 */
@Singleton
class AgentRunRecorder internal constructor(
    private val calls: CallSessionRepository,
    private val dao: AgentRunDao,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {

    @Inject
    constructor(
        calls: CallSessionRepository,
        dao: AgentRunDao,
        @ApplicationScope scope: CoroutineScope,
    ) : this(calls, dao, scope, System::currentTimeMillis)

    private val started = AtomicBoolean(false)

    /** Last row written per run, so an unchanged session costs no write. */
    private val rows = mutableMapOf<String, AgentCallRunEntity>()

    /** Last turn written per `(runId, seq)`, so a revised partial costs one. */
    private val turns = mutableMapOf<String, MutableMap<Int, AgentTurnEntity>>()

    private val ended = mutableMapOf<String, AgentRunEndReason>()

    /** Idempotent: the application calls it once, tests may call it again. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            merge(
                calls.sessions.map { Signal.Snapshot(it) },
                calls.events.map { Signal.Lifecycle(it) },
            ).collect { signal ->
                when (signal) {
                    is Signal.Snapshot -> onSessions(signal.sessions)
                    is Signal.Lifecycle -> onLifecycle(signal.event)
                }
            }
        }
    }

    private suspend fun onSessions(sessions: List<CallSession>) {
        for (session in sessions) {
            if (session.agentMode == AgentMode.NONE) continue
            recordRun(session)
            recordTurns(session)
        }
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        rows.keys.toList()
            .filterNot(live::contains)
            .forEach { finish(it, AgentRunEndReason.UNKNOWN) }
    }

    private suspend fun onLifecycle(event: CallLifecycleEvent) {
        if (event !is CallLifecycleEvent.Ended) return
        val reason =
            if (event.missed) AgentRunEndReason.MISSED else AgentRunEndReason.COMPLETED
        finish(event.session.id, reason)
    }

    private suspend fun recordRun(session: CallSession) {
        // A finished run is closed for good; re-upserting the row here would
        // wipe the endedAtMs/endReason that finishRun just set.
        if (session.id in ended) return
        val existing = rows[session.id]
        val row = AgentCallRunEntity(
            id = session.id,
            stack = session.stack.name,
            direction = session.direction.name,
            agentMode = session.agentMode.name,
            counterpartNumber = session.party.number,
            counterpartName = session.party.displayName,
            // Neither id is carried on CallSession today; the columns exist so
            // the schema does not have to change when they are.
            automationId = existing?.automationId,
            taskId = existing?.taskId,
            startedAtMs = existing?.startedAtMs ?: now(),
        )
        if (row == existing) return
        rows[session.id] = row
        dao.upsertRun(row)
    }

    private suspend fun recordTurns(session: CallSession) {
        val known = turns.getOrPut(session.id) { mutableMapOf() }
        val changed = mutableListOf<AgentTurnEntity>()
        session.timeline.forEachIndexed { seq, entry ->
            val turn = entry.toTurn(session.id, seq)
            if (known[seq] != turn) {
                known[seq] = turn
                changed += turn
            }
        }
        if (changed.isNotEmpty()) dao.upsertTurns(changed)
    }

    private suspend fun finish(runId: String, reason: AgentRunEndReason) {
        if (runId !in rows) return
        val previous = ended[runId]
        val upgradesUnknown =
            previous == AgentRunEndReason.UNKNOWN && reason != AgentRunEndReason.UNKNOWN
        if (previous != null && !upgradesUnknown) return
        ended[runId] = reason
        dao.finishRun(runId, now(), reason.name, outcomeSummary(runId))
    }

    /** The last thing anyone actually said, which is what the list row shows. */
    private fun outcomeSummary(runId: String): String? = turns[runId]
        ?.entries
        ?.sortedByDescending { it.key }
        ?.firstOrNull {
            it.value.speaker != AgentTurnSpeaker.SYSTEM.name && it.value.text.isNotBlank()
        }
        ?.value
        ?.text
        ?.take(SUMMARY_MAX_CHARS)

    private fun TimelineEntry.toTurn(runId: String, seq: Int): AgentTurnEntity = when (this) {
        is AgentUtterance -> turn(runId, seq, AgentTurnSpeaker.AGENT, text, complete)
        is CallerUtterance -> turn(runId, seq, AgentTurnSpeaker.CALLER, text, final)
        is UserReply -> turn(runId, seq, AgentTurnSpeaker.USER, text, final = true)
        // Markers have no text of their own; the kind is the record.
        is SystemEvent -> turn(runId, seq, AgentTurnSpeaker.SYSTEM, kind.name, final = true)
    }

    private fun TimelineEntry.turn(
        runId: String,
        seq: Int,
        speaker: AgentTurnSpeaker,
        text: String,
        final: Boolean,
    ) = AgentTurnEntity(
        runId = runId,
        seq = seq,
        speaker = speaker.name,
        text = text,
        atMs = atMs,
        final = final,
    )

    private sealed interface Signal {
        data class Snapshot(val sessions: List<CallSession>) : Signal

        data class Lifecycle(val event: CallLifecycleEvent) : Signal
    }

    private companion object {
        const val SUMMARY_MAX_CHARS = 180
    }
}
