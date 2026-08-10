package dev.triplex.voice

/**
 * Whether the gateway currently reports a usable cloned voice.
 *
 * A one-method seam over `GatewayApi.getVoiceProfile(...).synthesis_ready` so
 * the fail-closed rule in `AgentConfigRepository` (RUNTIME_INVARIANTS.md §7.6)
 * can be tested on the JVM without a gateway.
 */
fun interface VoiceProfileReadiness {
    /** False when the profile is missing, unprepared, or unreachable. */
    suspend fun synthesisReady(): Boolean
}
