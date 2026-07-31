package com.triplex.dialer.model

/**
 * Maps the four Triplex capabilities into Android-dialer-specific models.
 *
 * These are the capabilities from the Triplex voice agent that we wire into
 * the Android telecom stack and in-call UX.
 */

/** Echo cancellation state. */
enum class EchoCancellationState {
    ACTIVE,
    CALIBRATING,
    BYPASSED,
    ERROR
}

/** AWS Chime SDK meeting connection state. */
enum class ChimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/** Interruption (barge-in) state. */
enum class InterruptionState {
    IDLE,
    CALLER_SPEECH_DETECTED,
    TEARING_DOWN,
    TEARDOWN_COMPLETE,
    SUPPRESSED
}

/** Voice cloning profile — mirrors Triplex's Qwen3-TTS clone model. */
data class VoiceCloneProfile(
    val speakerId: String,
    val displayName: String,
    val consentVerified: Boolean = false,
    val referenceDurationSec: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** Overall Triplex engine status for a call. */
data class TriplexCallCapabilities(
    val echoCancellation: EchoCancellationState = EchoCancellationState.CALIBRATING,
    val chimeConnection: ChimeConnectionState = ChimeConnectionState.DISCONNECTED,
    val interruption: InterruptionState = InterruptionState.IDLE,
    val voiceClone: VoiceCloneProfile? = null,
    val pipelineLatencyMs: Long = 0L,
)
