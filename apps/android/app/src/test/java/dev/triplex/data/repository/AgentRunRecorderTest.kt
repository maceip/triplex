package dev.triplex.data.repository

import dev.triplex.data.local.db.AgentRunEndReason
import dev.triplex.data.local.db.AgentTurnSpeaker
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallCapabilities
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallParty
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.SystemEvent
import dev.triplex.domain.call.SystemEventKind
import dev.triplex.domain.call.TimelineEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run recorder, exercised through the real [CallSessionRepository] seam.
 *
 * No Room and no Robolectric: [FakeAgentRunDao] gives the same
 * upsert-by-primary-key semantics the DAO contract promises, and the recorder's
 * own coroutine runs on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunRecorderTest {

    private val calls = FakeCallSessionRepository()
    private val dao = FakeAgentRunDao()
    private var clock = 1_000L

    @Test
    fun `agent attaching creates a run`() = runTest(UnconfinedTestDispatcher()) {
        recorder()

        calls.emitSessions(session())

        val run = requireNotNull(dao.run(SESSION_ID))
        assertEquals(AgentMode.SCREENING.name, run.agentMode)
        assertEquals(CallStack.SIP.name, run.stack)
        assertEquals(CallDirection.INCOMING.name, run.direction)
        assertEquals("+15551234567", run.counterpartNumber)
        assertEquals(1_000L, run.startedAtMs)
        assertNull(run.endedAtMs)
        assertNull(run.endReason)
    }

    @Test
    fun `a call the user simply takes leaves no run`() = runTest(UnconfinedTestDispatcher()) {
        recorder()

        calls.emitSessions(session(agentMode = AgentMode.NONE))

        assertNull(dao.run(SESSION_ID))
        assertEquals(0, dao.runWrites)
    }

    @Test
    fun `turns are written as the timeline grows`() = runTest(UnconfinedTestDispatcher()) {
        recorder()

        calls.emitSessions(session(timeline = listOf(SystemEvent(SystemEventKind.CALL_STARTED, 1))))
        calls.emitSessions(
            session(
                timeline = listOf(
                    SystemEvent(SystemEventKind.CALL_STARTED, 1),
                    AgentUtterance(text = "Who is calling?", atMs = 2),
                ),
            ),
        )

        val turns = dao.turnsOf(SESSION_ID)
        assertEquals(2, turns.size)
        assertEquals(listOf(0, 1), turns.map { it.seq })
        assertEquals(AgentTurnSpeaker.SYSTEM.name, turns[0].speaker)
        assertEquals(SystemEventKind.CALL_STARTED.name, turns[0].text)
        assertEquals(AgentTurnSpeaker.AGENT.name, turns[1].speaker)
        assertEquals("Who is calling?", turns[1].text)
    }

    @Test
    fun `a revised partial rewrites the same turn`() = runTest(UnconfinedTestDispatcher()) {
        recorder()

        calls.emitSessions(
            session(timeline = listOf(CallerUtterance(text = "Hi this", atMs = 5, final = false))),
        )
        calls.emitSessions(
            session(
                timeline = listOf(
                    CallerUtterance(text = "Hi this is Dana", atMs = 5, final = true),
                ),
            ),
        )

        val turns = dao.turnsOf(SESSION_ID)
        assertEquals(1, turns.size)
        assertEquals(0, turns.single().seq)
        assertEquals("Hi this is Dana", turns.single().text)
        assertTrue(turns.single().final)
    }

    @Test
    fun `an unchanged session costs no write`() = runTest(UnconfinedTestDispatcher()) {
        recorder()

        val snapshot = session(timeline = listOf(CallerUtterance(text = "Hello", atMs = 5)))
        calls.emitSessions(snapshot)
        val runWrites = dao.runWrites
        val turnWrites = dao.turnWrites
        // A real re-emission that changes nothing the run history records.
        calls.emitSessions(snapshot.copy(isMuted = true))

        assertEquals(runWrites, dao.runWrites)
        assertEquals(turnWrites, dao.turnWrites)
    }

    @Test
    fun `a completed call is closed with its last spoken turn`() =
        runTest(UnconfinedTestDispatcher()) {
            recorder()
            val live = session(
                timeline = listOf(
                    CallerUtterance(text = "It is Dana from the clinic", atMs = 5),
                    SystemEvent(SystemEventKind.CALL_ENDED, 6),
                ),
            )
            calls.emitSessions(live)

            clock = 9_000L
            calls.emitEvent(CallLifecycleEvent.Ended(live, missed = false))

            val run = requireNotNull(dao.run(SESSION_ID))
            assertEquals(AgentRunEndReason.COMPLETED.name, run.endReason)
            assertEquals(9_000L, run.endedAtMs)
            // The system marker is not what anyone said, so it is not the summary.
            assertEquals("It is Dana from the clinic", run.outcomeSummary)
        }

    @Test
    fun `a missed call is recorded as missed`() = runTest(UnconfinedTestDispatcher()) {
        recorder()
        val live = session()
        calls.emitSessions(live)

        calls.emitEvent(CallLifecycleEvent.Ended(live, missed = true))

        assertEquals(AgentRunEndReason.MISSED.name, requireNotNull(dao.run(SESSION_ID)).endReason)
    }

    @Test
    fun `a run interrupted mid-call stays visibly unfinished`() =
        runTest(UnconfinedTestDispatcher()) {
            recorder()

            // The process dies here: turns landed, no lifecycle event ever came,
            // and the session list is never updated again.
            calls.emitSessions(
                session(timeline = listOf(CallerUtterance(text = "Half a sentence", atMs = 5))),
            )

            val run = requireNotNull(dao.run(SESSION_ID))
            assertNull(run.endedAtMs)
            assertNull(run.endReason)
            assertNull(run.outcomeSummary)
            assertEquals(1, dao.turnsOf(SESSION_ID).size)
        }

    @Test
    fun `a session that vanishes closes as unknown and a late Ended upgrades it`() =
        runTest(UnconfinedTestDispatcher()) {
            recorder()
            val live = session(timeline = listOf(CallerUtterance(text = "Dana here", atMs = 5)))
            calls.emitSessions(live)

            calls.emitSessions()
            assertEquals(
                AgentRunEndReason.UNKNOWN.name,
                requireNotNull(dao.run(SESSION_ID)).endReason,
            )

            clock = 12_000L
            calls.emitEvent(CallLifecycleEvent.Ended(live, missed = false))

            val run = requireNotNull(dao.run(SESSION_ID))
            assertEquals(AgentRunEndReason.COMPLETED.name, run.endReason)
            assertEquals(12_000L, run.endedAtMs)
        }

    @Test
    fun `a closed run is not reopened by a late session snapshot`() =
        runTest(UnconfinedTestDispatcher()) {
            recorder()
            val live = session()
            calls.emitSessions(live)
            calls.emitEvent(CallLifecycleEvent.Ended(live, missed = false))

            calls.emitSessions(live.copy(phase = CallPhase.ENDING))

            val run = requireNotNull(dao.run(SESSION_ID))
            assertEquals(AgentRunEndReason.COMPLETED.name, run.endReason)
            assertFalse(run.endedAtMs == null)
        }

    /** Runs on [TestScope.backgroundScope], so the collector is torn down with the test. */
    private fun TestScope.recorder(): AgentRunRecorder =
        AgentRunRecorder(calls, dao, backgroundScope) { clock }.also(AgentRunRecorder::start)

    private fun session(
        agentMode: AgentMode = AgentMode.SCREENING,
        timeline: List<TimelineEntry> = emptyList(),
    ) = CallSession(
        id = SESSION_ID,
        stack = CallStack.SIP,
        direction = CallDirection.INCOMING,
        party = CallParty(number = "+15551234567", displayName = "Unknown caller"),
        phase = CallPhase.SCREENING,
        agentMode = agentMode,
        connectedAtMs = null,
        capabilities = CallCapabilities(agentHasMedia = true),
        timeline = timeline,
        otherCallCount = 0,
        statusLabel = "Screening",
        statusMessage = "",
    )

    private companion object {
        const val SESSION_ID = "sip-1"
    }
}
