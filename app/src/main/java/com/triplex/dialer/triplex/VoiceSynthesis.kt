package com.triplex.dialer.triplex

import com.triplex.dialer.Constants
import com.triplex.dialer.model.VoiceCloneProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice synthesis engine for Android — dual router.
 *
 * Mirrors Triplex's dual_router.py and qwen3_tts_engine.py:
 * - Kokoro TTS (branded, low-latency) for default voice
 * - Qwen3-TTS Base (zero-shot clone) for named profiles
 * - Router selects per call; clone missing → fail closed, never fallback
 *
 * The in-call UX shows which voice is active and clone status.
 */
class VoiceSynthesis(private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {

    enum class ActiveEngine { KOKORO, QWEN3_CLONE }

    private val _activeEngine = MutableStateFlow(ActiveEngine.KOKORO)
    val activeEngine: StateFlow<ActiveEngine> = _activeEngine

    private var currentCloneProfile: VoiceCloneProfile? = null

    /** Select synthesis engine for a call. */
    fun selectEngine(cloneProfile: VoiceCloneProfile? = null): ActiveEngine {
        if (cloneProfile != null && cloneProfile.consentVerified) {
            currentCloneProfile = cloneProfile
            _activeEngine.value = ActiveEngine.QWEN3_CLONE
            return ActiveEngine.QWEN3_CLONE
        }
        _activeEngine.value = ActiveEngine.KOKORO
        return ActiveEngine.KOKORO
    }

    /** Synthesize text to PCM audio. Returns 16-bit mono PCM at 16 kHz. */
    suspend fun synthesize(text: String, cloneProfile: VoiceCloneProfile? = null): ShortArray {
        return when (_activeEngine.value) {
            ActiveEngine.KOKORO -> synthesizeKokoro(text)
            ActiveEngine.QWEN3_CLONE -> synthesizeClone(text, cloneProfile ?: currentCloneProfile)
        }
    }

    private suspend fun synthesizeKokoro(text: String): ShortArray {
        // Placeholder — real impl calls Kokoro TTS engine (on-device or cloud)
        // Kokoro produces 24 kHz PCM → resample to 16 kHz / 20 ms frames
        delay(50) // target latency
        return generateSilence(320) // 20 ms of silence
    }

    private suspend fun synthesizeClone(text: String, profile: VoiceCloneProfile?): ShortArray {
        if (profile == null || !profile.consentVerified) {
            // Fail closed — never fall back to Kokoro silently
            throw IllegalStateException("Clone profile missing or unverified")
        }

        // Placeholder — real impl calls Qwen3-TTS Base
        // Requires CUDA FlashAttention 2 when available
        delay(100) // target latency
        return generateSilence(320)
    }

    /** Reset synthesis engine on call end. */
    fun reset() {
        currentCloneProfile = null
        _activeEngine.value = ActiveEngine.KOKORO
    }

    private fun generateSilence(samples: Int): ShortArray = ShortArray(samples)
}
