package dev.triplex.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device TTS benchmark for the Phase 4 placement decision
 * (FINAL_UNIFICATION.md).
 *
 * Measures the branded-slot candidate (Inflect-Micro-v2, shipped as an APK
 * asset) against the rule fixed in advance: real-time factor above 1.5 and
 * first chunk under 300 ms means synthesis ships on device.
 *
 * The models are bundled rather than downloaded so the measurement reflects
 * how the product would actually ship.
 */
@Singleton
class TtsBenchmark @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Token counts standing in for short, medium, and long agent turns. */
    private val turns = listOf("short" to 32, "medium" to 96, "long" to 192)

    suspend fun run(threads: Int = 4, runsPerTurn: Int = 5): String =
        withContext(Dispatchers.Default) {
            val duration = session("tts/duration.onnx", threads)
            val decode = session("tts/decode.onnx", threads)
            val report = StringBuilder()
            try {
                synthesize(duration, decode, 32) // warm up

                for ((label, tokens) in turns) {
                    val samples = LongArray(runsPerTurn)
                    var audioSamples = 0
                    repeat(runsPerTurn) { index ->
                        val (nanos, produced) = synthesize(duration, decode, tokens)
                        samples[index] = nanos
                        audioSamples = produced
                    }
                    samples.sort()
                    val medianMs = samples[runsPerTurn / 2] / 1_000_000.0
                    val audioSeconds = audioSamples / SAMPLE_RATE.toDouble()
                    val rtf = audioSeconds / (medianMs / 1000.0)
                    val line = "%s threads=%d tokens=%d audio=%.2fs synth=%.1fms RTF=%.2f"
                        .format(label, threads, tokens, audioSeconds, medianMs, rtf)
                    Timber.i("TTS-BENCH %s", line)
                    report.appendLine(line)
                }
            } catch (e: Exception) {
                Timber.e(e, "TTS benchmark failed")
                report.appendLine("FAILED: ${e.message}")
            } finally {
                runCatching { duration.close() }
                runCatching { decode.close() }
            }
            report.toString()
        }

    /** Returns (elapsed nanos, produced sample count). */
    private fun synthesize(
        duration: OrtSession,
        decode: OrtSession,
        tokenCount: Int
    ): Pair<Long, Int> {
        val random = Random(0)
        val tokens = LongArray(tokenCount) { random.nextInt(1, 100).toLong() }
        var producedSamples = 0

        val nanos = measureNanoTime {
            val tokenTensor = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(tokens), longArrayOf(1, tokenCount.toLong())
            )
            val lengths = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(longArrayOf(tokenCount.toLong())), longArrayOf(1)
            )
            val lengthScale = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf()
            )

            val stage1 = duration.run(
                mapOf(
                    "tokens" to tokenTensor,
                    "lengths" to lengths,
                    "length_scale" to lengthScale
                )
            )
            val mP = stage1.get(0) as OnnxTensor
            val logsP = stage1.get(1) as OnnxTensor
            val yMask = stage1.get(2) as OnnxTensor

            val shape = mP.info.shape // [1, 192, mel_len]
            val noiseCount = (shape[0] * shape[1] * shape[2]).toInt()
            val noise = FloatArray(noiseCount) { random.nextFloat() * 2f - 1f }
            val noiseTensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(noise), shape
            )
            val noiseScale = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(floatArrayOf(0.667f)), longArrayOf()
            )

            val stage2 = decode.run(
                mapOf(
                    "m_p_exp" to mP,
                    "logs_p_exp" to logsP,
                    "y_mask" to yMask,
                    "zp_noise" to noiseTensor,
                    "noise_scale" to noiseScale
                )
            )
            val waveform = stage2.get(0) as OnnxTensor
            producedSamples = waveform.info.shape.fold(1L) { a, b -> a * b }.toInt()

            stage2.close(); stage1.close()
            tokenTensor.close(); lengths.close(); lengthScale.close()
            noiseTensor.close(); noiseScale.close()
        }
        return nanos to producedSamples
    }

    private fun session(asset: String, threads: Int): OrtSession {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(asset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return environment.createSession(file.absolutePath, options)
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}
