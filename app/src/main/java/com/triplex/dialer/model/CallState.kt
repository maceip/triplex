package com.triplex.dialer.model

import androidx.core.telecom.CallsManager

/**
 * Mirrors AndroidX core-telecom CallState with Triplex-specific extensions.
 * Represents the lifecycle of a single call.
 */
enum class CallState {
    NEW,
    RINGING,
    ACTIVE,
    ON_HOLD,
    LOCAL_HOLD,
    DISCONNECTED,
    DISCONNECTING;

    /** Map to CallsManager telecom state for AndroidX core-telecom integration. */
    fun toTelecomState(): Int = when (this) {
        NEW -> CallsManager.CALL_STATE_NEW
        RINGING -> CallsManager.CALL_STATE_RINGING
        ACTIVE -> CallsManager.CALL_STATE_ACTIVE
        ON_HOLD -> CallsManager.CALL_STATE_ON_HOLD
        LOCAL_HOLD -> CallsManager.CALL_STATE_LOCAL_HOLD
        DISCONNECTED -> CallsManager.CALL_STATE_DISCONNECTED
        DISCONNECTING -> CallsManager.CALL_STATE_DISCONNECTING
    }

    companion object {
        fun fromTelecomState(state: Int): CallState = when (state) {
            CallsManager.CALL_STATE_NEW -> NEW
            CallsManager.CALL_STATE_RINGING -> RINGING
            CallsManager.CALL_STATE_ACTIVE -> ACTIVE
            CallsManager.CALL_STATE_ON_HOLD -> ON_HOLD
            CallsManager.CALL_STATE_LOCAL_HOLD -> LOCAL_HOLD
            CallsManager.CALL_STATE_DISCONNECTED -> DISCONNECTED
            CallsManager.CALL_STATE_DISCONNECTING -> DISCONNECTING
            else -> DISCONNECTED
        }
    }
}
