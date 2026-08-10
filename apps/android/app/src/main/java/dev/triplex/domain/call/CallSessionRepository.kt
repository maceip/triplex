package dev.triplex.domain.call

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of call state and the single entry point for call actions
 * (reskin.md §2.3).
 *
 * Merges the Telecom (SIM) and Plivo/SIP stacks into one stream of
 * [CallSession]s and routes [CallIntent]s back to whichever stack owns the
 * target session, so no surface has to know which stack it is looking at.
 */
interface CallSessionRepository {
    /**
     * The session the call surface should render, or null when there is none.
     *
     * With both stacks live this is the Telecom call: it owns the device's
     * audio and the platform's ringer, so it is what the user is actually
     * being asked about.
     */
    val session: StateFlow<CallSession?>

    /** Every live session, primary first. */
    val sessions: StateFlow<List<CallSession>>

    /**
     * Lifecycle transitions, derived from [session].
     *
     * Consumed today by the notification controller; it is also the seam the
     * Phase 3 run recorder attaches to instead of polling call state.
     */
    val events: SharedFlow<CallLifecycleEvent>

    /**
     * @param sessionId the session to act on. Null targets the primary
     *   session, which is what every in-app surface wants; notifications pass
     *   an explicit id because the notification for a backgrounded SIP call
     *   must not act on a Telecom call that has since become primary.
     */
    fun dispatch(intent: CallIntent, sessionId: String? = null)
}

sealed interface CallLifecycleEvent {
    val session: CallSession

    data class Started(override val session: CallSession) : CallLifecycleEvent

    data class Connected(override val session: CallSession) : CallLifecycleEvent

    data class Ended(
        override val session: CallSession,
        val missed: Boolean,
    ) : CallLifecycleEvent
}
