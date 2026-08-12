package dev.triplex.voice

/**
 * Whether a usable cloned voice is available on this device.
 *
 * A one-method seam over [LocalVoicePromptStore.synthesisReady] so the
 * fail-closed rule in `AgentConfigRepository` (RUNTIME_INVARIANTS.md §7.6)
 * can be tested on the JVM without Android model files.
 */
fun interface VoiceProfileReadiness {
    /** False when the local profile or on-device models are missing. */
    suspend fun synthesisReady(): Boolean
}
