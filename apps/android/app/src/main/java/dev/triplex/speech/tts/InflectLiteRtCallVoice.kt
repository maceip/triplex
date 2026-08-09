package dev.triplex.speech.tts

import android.content.Context
import com.kokoro.EnglishPhonemizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Small, CPU-first screening voice for the live SIP path.
 *
 * Inflect Nano v2 is two dynamic LiteRT graphs. The frontend is the
 * Apache-licensed Kokoro neural G2P plus CMU dictionary, remapped onto
 * Inflect's compatible StyleTTS2 symbol table. Audio is generated at 24 kHz
 * and converted to the native media runtime's canonical 16 kHz mono PCM.
 */
@Singleton
class InflectLiteRtCallVoice @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val inferenceMutex = Mutex()

    private val vocabulary: Map<String, Int> by lazy {
        buildList {
            add("_")
            addAll(";:,.!?¡¿—…\"«»“” ".map(Char::toString))
            addAll("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".map(Char::toString))
            addAll(IPA_SYMBOLS.map(Char::toString))
        }.withIndex().associate { (index, symbol) -> symbol to index }
    }

    private val phonemizer by lazy {
        EnglishPhonemizer.load(context, vocabulary)
    }

    private val encoderDelegate = lazy {
        Interpreter(loadAsset(ENCODER_ASSET), interpreterOptions())
    }
    private val encoder by encoderDelegate

    private val decoderDelegate = lazy {
        Interpreter(loadAsset(DECODER_ASSET), interpreterOptions())
    }
    private val decoder by decoderDelegate

    suspend fun synthesize(text: String): ShortArray = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            runCatching { synthesizeLocked(text) }
                .onFailure { Timber.e(it, "Inflect LiteRT synthesis failed") }
                .getOrDefault(ShortArray(0))
        }
    }

    fun close() {
        if (encoderDelegate.isInitialized()) runCatching { encoder.close() }
        if (decoderDelegate.isInitialized()) runCatching { decoder.close() }
    }

    fun shutdown() = close()

    private fun synthesizeLocked(text: String): ShortArray {
        val phonemeIds = phonemizer.phonemize(text.trim())
        if (phonemeIds.isEmpty()) return ShortArray(0)

        // VITS blank interspersion: [blank, p0, blank, p1, ..., blank].
        val tokens = IntArray(phonemeIds.size * 2 + 1)
        phonemeIds.forEachIndexed { index, token -> tokens[index * 2 + 1] = token }

        encoder.resizeInput(0, intArrayOf(1, tokens.size))
        encoder.allocateTensors()

        val mean = Array(1) { Array(tokens.size) { FloatArray(CHANNELS) } }
        val logScale = Array(1) { Array(tokens.size) { FloatArray(CHANNELS) } }
        val logDuration = Array(1) { Array(tokens.size) { FloatArray(1) } }
        encoder.runForMultipleInputsOutputs(
            arrayOf(arrayOf(tokens)),
            mutableMapOf<Int, Any>(0 to mean, 1 to logScale, 2 to logDuration)
        )

        val durations = IntArray(tokens.size) { index ->
            ceil(exp(logDuration[0][index][0].toDouble()) / SPEED)
                .toInt()
                .coerceIn(0, MAX_FRAMES_PER_TOKEN)
        }
        val totalFrames = durations.sum().coerceAtMost(MAX_TOTAL_FRAMES)
        if (totalFrames == 0) return ShortArray(0)

        val latent = Array(1) { Array(totalFrames) { FloatArray(CHANNELS) } }
        val random = GaussianRandom(seed = text.hashCode())
        var frame = 0
        for (tokenIndex in durations.indices) {
            repeat(durations[tokenIndex]) {
                if (frame >= totalFrames) return@repeat
                for (channel in 0 until CHANNELS) {
                    latent[0][frame][channel] = mean[0][tokenIndex][channel] +
                        random.next() * exp(logScale[0][tokenIndex][channel].toDouble()).toFloat() * VARIATION
                }
                frame++
            }
            if (frame >= totalFrames) break
        }

        decoder.resizeInput(0, intArrayOf(1, totalFrames, CHANNELS))
        decoder.allocateTensors()
        val waveform = Array(1) { FloatArray(totalFrames * SAMPLES_PER_FRAME) }
        decoder.run(latent, waveform)
        applyEdgeFade(waveform[0])
        return resampleTo16k(waveform[0])
    }

    private fun loadAsset(path: String): ByteBuffer {
        val bytes = context.assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    private fun interpreterOptions() = Interpreter.Options().apply {
        setNumThreads(4)
        setUseXNNPACK(true)
    }

    private fun applyEdgeFade(samples: FloatArray) {
        val count = min(FADE_SAMPLES, samples.size / 2)
        if (count == 0) return
        for (index in 0 until count) {
            val gain = index.toFloat() / count
            samples[index] *= gain
            samples[samples.lastIndex - index] *= gain
        }
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

    private class GaussianRandom(seed: Int) {
        private val random = Random(seed)
        private var spare: Float? = null

        fun next(): Float {
            spare?.let {
                spare = null
                return it
            }
            val u1 = max(random.nextDouble(), 1e-12)
            val u2 = random.nextDouble()
            val magnitude = sqrt(-2.0 * ln(u1))
            spare = (magnitude * kotlin.math.sin(2.0 * PI * u2)).toFloat()
            return (magnitude * cos(2.0 * PI * u2)).toFloat()
        }
    }

    private companion object {
        const val ENCODER_ASSET = "models/tts/inflect/inflect_text_encoder.tflite"
        const val DECODER_ASSET = "models/tts/inflect/inflect_decoder.tflite"
        const val CHANNELS = 128
        const val SAMPLES_PER_FRAME = 256
        const val MODEL_RATE = 24_000
        const val OUTPUT_RATE = 16_000
        const val SPEED = 1.0
        const val VARIATION = 0.667f
        const val MAX_FRAMES_PER_TOKEN = 64
        const val MAX_TOTAL_FRAMES = 2_400
        const val FADE_SAMPLES = MODEL_RATE / 200

        const val IPA_SYMBOLS =
            "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
    }
}
