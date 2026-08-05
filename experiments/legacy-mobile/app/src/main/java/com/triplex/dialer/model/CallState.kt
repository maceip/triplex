package com.triplex.dialer.model

/**
 * Triplex call lifecycle states.
 *
 * Maps to Android telecom CallState integers for core-telecom integration:
 * - NEW / RINGING → incoming call pending answer
 * - ACTIVE → call connected with two-way audio
 * - ON_HOLD / LOCAL_HOLD → hold states
 * - DISCONNECTED / DISCONNECTING → call termination
 */
enum class CallState {
    NEW,
    RINGING,
    ACTIVE,
    ON_HOLD,
    LOCAL_HOLD,
    DISCONNECTED,
    DISCONNECTING;

    companion object {
        /** Map from Android telecom state integers. */
        fun fromTelecomState(state: Int): CallState = when (state) {
            0 -> NEW       // CALL_STATE_NEW
            1 -> RINGING   // CALL_STATE_RINGING
            2 -> ACTIVE    // CALL_STATE_ACTIVE
            3 -> ON_HOLD   // CALL_STATE_ON_HOLD
            4 -> LOCAL_HOLD
            5 -> DISCONNECTED
            6 -> DISCONNECTING
            else -> DISCONNECTED
        }

        /** Map to Android telecom state integer. */
        fun CallState.toTelecomState(): Int = when (this) {
            NEW -> 0
            RINGING -> 1
            ACTIVE -> 2
            ON_HOLD -> 3
            LOCAL_HOLD -> 4
            DISCONNECTED -> 5
            DISCONNECTING -> 6
        }
    }
}
