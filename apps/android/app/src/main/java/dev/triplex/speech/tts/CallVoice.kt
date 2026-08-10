package dev.triplex.speech.tts

import kotlinx.coroutines.flow.Flow

/**
 * Live-call speech synthesizer. Emits 16 kHz mono PCM chunks suitable for
 * [dev.triplex.telephony.plivo.PlivoSipEndpoint] streaming synthesis.
 */
interface CallVoice {
    /**
     * Synthesizes [text] as a stream of PCM chunks. Collectors must stop when
     * the flow completes or when [cancel] is invoked (cooperative cancel).
     */
    fun synthesizeStream(text: String): Flow<ShortArray>

    /** Cooperative cancel of any in-flight [synthesizeStream]. */
    fun cancel()

    fun close()
}
