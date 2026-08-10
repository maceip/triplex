package dev.triplex.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device Qwen3 ECAPA-TDNN speaker encoder.
 *
 * Mel front-end matches `scripts/export_qwen3_speaker_encoder.py:compute_mel`
 * (24 kHz, n_fft=1024, hop=256, 128 mels, slaney basis). TFLite input is
 * channel-second `[1, 128, 469]`; output is a raw 1024-d x-vector.
 */
@Singleton
class SpeakerEncoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promptStore: LocalVoicePromptStore,
) {
    private val modelsDir: File get() = promptStore.modelsDir()
    private val interpreter by lazy {
        val model = File(modelsDir, "speaker_encoder.tflite")
        check(model.isFile) {
            "Missing on-device speaker encoder at ${model.absolutePath}. Push models first."
        }
        Interpreter(
            model,
            Interpreter.Options().apply { setNumThreads(4) },
        )
    }
    private val melBasis: Array<FloatArray> by lazy {
        val file = File(modelsDir, "mel_basis_slaney_128.npy")
        check(file.isFile) { "Missing mel basis at ${file.absolutePath}" }
        val flat = readFloat32Npy(file)
        require(flat.size == NUM_MELS * (N_FFT / 2 + 1)) {
            "Unexpected mel basis size ${flat.size}"
        }
        Array(NUM_MELS) { m ->
            FloatArray(N_FFT / 2 + 1) { k -> flat[m * (N_FFT / 2 + 1) + k] }
        }
    }

    /** Encodes 24 kHz mono PCM into a 1024-d speaker embedding. */
    fun encode(pcm24k: ShortArray): FloatArray {
        val audio = FloatArray(pcm24k.size) { pcm24k[it] / 32768f }
        val melTimeMajor = computeMel(audio, NUM_FRAMES) // [frames, mels]
        // TFLite wants [1, mels, frames]
        val input = Array(1) {
            Array(NUM_MELS) { m ->
                FloatArray(NUM_FRAMES) { t -> melTimeMajor[t][m] }
            }
        }
        val output = Array(1) { FloatArray(EMBED_DIM) }
        synchronized(interpreter) {
            interpreter.run(input, output)
        }
        val embedding = output[0]
        check(embedding.all { it.isFinite() }) { "Speaker encoder returned non-finite values" }
        Timber.i("Speaker embedding extracted: dim=%d", embedding.size)
        return embedding
    }

    /** Centroid of several embeddings (L2 of mean; raw mean is fine for Qwen). */
    fun centroid(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty())
        val out = FloatArray(EMBED_DIM)
        for (emb in embeddings) {
            require(emb.size == EMBED_DIM)
            for (i in 0 until EMBED_DIM) out[i] += emb[i]
        }
        val n = embeddings.size.toFloat()
        for (i in 0 until EMBED_DIM) out[i] /= n
        return out
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    private fun computeMel(audio: FloatArray, numFrames: Int): Array<FloatArray> {
        val padding = (N_FFT - HOP) / 2
        val padded = FloatArray(audio.size + 2 * padding)
        // reflect pad
        for (i in 0 until padding) {
            padded[i] = audio[min(padding - i, audio.lastIndex)]
            padded[padded.lastIndex - i] = audio[max(0, audio.lastIndex - (padding - i))]
        }
        System.arraycopy(audio, 0, padded, padding, audio.size)

        val window = FloatArray(WIN) { n ->
            (0.5 - 0.5 * cos(2.0 * PI * n / WIN)).toFloat()
        }
        val frames = ArrayList<FloatArray>()
        var offset = 0
        while (offset + N_FFT <= padded.size) {
            val frame = FloatArray(N_FFT) { i -> padded[offset + i] * window[i] }
            val mag = stftMagnitude(frame)
            val mel = FloatArray(NUM_MELS)
            for (m in 0 until NUM_MELS) {
                var acc = 0f
                val basis = melBasis[m]
                for (k in basis.indices) acc += basis[k] * mag[k]
                mel[m] = ln(max(acc, 1e-5f))
            }
            frames.add(mel)
            offset += HOP
        }
        if (frames.isEmpty()) {
            return Array(numFrames) { FloatArray(NUM_MELS) }
        }
        return when {
            frames.size >= numFrames -> Array(numFrames) { frames[it] }
            else -> {
                val last = frames.last()
                Array(numFrames) { t -> if (t < frames.size) frames[t] else last.copyOf() }
            }
        }
    }

    /** Real FFT magnitude via radix-2 Cooley–Tukey (frame length must be power of two). */
    private fun stftMagnitude(frame: FloatArray): FloatArray {
        val n = frame.size
        val re = DoubleArray(n) { frame[it].toDouble() }
        val im = DoubleArray(n)
        fft(re, im)
        val bins = n / 2 + 1
        return FloatArray(bins) { k ->
            sqrt((re[k] * re[k] + im[k] * im[k] + 1e-9).toFloat())
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun readFloat32Npy(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes[0] == 0x93.toByte()) { "not npy" }
        val headerLen = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
        val dataOffset = 10 + headerLen
        val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val N_FFT = 1_024
        const val HOP = 256
        const val WIN = 1_024
        const val NUM_MELS = 128
        const val NUM_FRAMES = 469
        const val EMBED_DIM = 1_024
    }
}
