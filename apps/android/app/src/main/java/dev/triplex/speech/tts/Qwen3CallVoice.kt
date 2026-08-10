package dev.triplex.speech.tts

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import dev.triplex.speech.tts.qwen3.Qwen3TtsEngine
import dev.triplex.voice.LocalVoicePromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.min

/**
 * CLONED call voice: on-device Qwen3-TTS-12Hz LiteRT conditioned on the
 * enrolled local x-vector. Emits 16 kHz PCM chunks for SIP streaming.
 */
@Singleton
class Qwen3CallVoice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promptStore: LocalVoicePromptStore,
) : CallVoice {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var engine: Qwen3TtsEngine? = null

    private fun ensureEngine(): Qwen3TtsEngine {
        engine?.let { return it }
        val dir = promptStore.modelsDir()
        check(dir.isDirectory) { "Qwen3 models missing at ${dir.absolutePath}" }
        return Qwen3TtsEngine(dir).also { engine = it }
    }

    override fun synthesizeStream(text: String): Flow<ShortArray> = callbackFlow {
        cancelled.set(false)
        val spk = promptStore.readXVector()
            ?: throw IllegalStateException("No local speaker x-vector; enroll first")
        val eng = ensureEngine()
        try {
            eng.synthesize(
                text = text.trim(),
                language = "english",
                spk = spk,
                greedy = false,
                pcmSink = Qwen3TtsEngine.PcmSink { samples24k ->
                    if (cancelled.get()) return@PcmSink false
                    val pcm16 = resampleTo16k(samples24k)
                    if (pcm16.isNotEmpty()) {
                        trySend(pcm16)
                    }
                    !cancelled.get()
                },
            )
        } catch (t: Throwable) {
            Timber.e(t, "Qwen3 streaming synthesis failed")
            close(t)
            return@callbackFlow
        }
        close()
        awaitClose { cancelled.set(true) }
    }.flowOn(Dispatchers.Default)

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        cancel()
        engine?.close()
        engine = null
    }

    private fun resampleTo16k(input: FloatArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val outputSize = (input.size.toLong() * OUTPUT_RATE / MODEL_RATE).toInt()
        return ShortArray(outputSize) { index ->
            val source = index.toDouble() * MODEL_RATE / OUTPUT_RATE
            val left = floor(source).toInt().coerceAtMost(input.lastIndex)
            val right = min(left + 1, input.lastIndex)
            val fraction = (source - left).toFloat()
            val sample = input[left] + (input[right] - input[left]) * fraction
            (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val MODEL_RATE = 24_000L
        const val OUTPUT_RATE = 16_000L
    }
}
