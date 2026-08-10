package dev.triplex.data.repository

import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [CallSessionRepository] whose two streams are driven by the test.
 *
 * Keeps the same shape as the real one — `session` is the primary session,
 * `sessions` is every live session, `events` is lifecycle — so a test can
 * reproduce the ordering hazards the recorder documents: a session that
 * disappears without an `Ended`, and an `Ended` that lands after it did.
 */
class FakeCallSessionRepository : CallSessionRepository {

    private val liveSessions = MutableStateFlow<List<CallSession>>(emptyList())
    private val primarySession = MutableStateFlow<CallSession?>(null)

    // Buffered so a test can emit without a collector having to be parked on it.
    private val lifecycle = MutableSharedFlow<CallLifecycleEvent>(extraBufferCapacity = 16)

    override val session: StateFlow<CallSession?> = primarySession.asStateFlow()

    override val sessions: StateFlow<List<CallSession>> = liveSessions.asStateFlow()

    override val events: SharedFlow<CallLifecycleEvent> = lifecycle.asSharedFlow()

    val dispatched = mutableListOf<Pair<CallIntent, String?>>()

    override fun dispatch(intent: CallIntent, sessionId: String?) {
        dispatched += intent to sessionId
    }

    fun emitSessions(vararg sessions: CallSession) {
        liveSessions.value = sessions.toList()
        primarySession.value = sessions.firstOrNull()
    }

    suspend fun emitEvent(event: CallLifecycleEvent) {
        lifecycle.emit(event)
    }
}
