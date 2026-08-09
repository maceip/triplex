package dev.triplex.audio8bench

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class Audio8Engine(
    context: Context,
    private val report: (String) -> Unit,
) {
    private val root = requireNotNull(context.getExternalFilesDir(null))
    private val models = File(root, "models")
    private val fixture = File(root, "fixture")
    private val outputWav = File(root, "audio8_clone.wav")
    private val threads = max(1, Runtime.getRuntime().availableProcessors() - 1)

    fun run() {
        val metadata = JSONObject(requiredFixture("metadata.json").readText())
        val promptLength = metadata.getInt("prompt_length")
        val semanticBegin = metadata.getInt("semantic_begin_id")
        val padToken = metadata.getLong("pad_token_id")
        val prefix = readLongs(requiredFixture("prefix_ids_i64.bin"))
        val suffix = readLongs(requiredFixture("suffix_ids_i64.bin"))
        val expectedCodes = readLongs(requiredFixture("expected_codes_i64.bin"))

        val encoded = timed("codec_encoder") {
            val audio = direct(requiredFixture("reference_audio_f32.bin").readBytes())
            val codes = bytes(Long.SIZE_BYTES * 10 * 108)
            interpreter("audio8_codec_encoder_5s.tflite").use { model ->
                model.runSignature(
                    mapOf("args_0" to audio),
                    mapOf("output_0" to codes),
                    SIGNATURE,
                )
            }
            readLongs(codes, 10 * 108)
        }
        val mismatches = encoded.indices.count { encoded[it] != expectedCodes[it] }
        report("encoder_codes mismatches=$mismatches/${encoded.size}")

        val prompt = LongArray(11 * PREFILL_LENGTH)
        for (index in 0 until PREFILL_LENGTH) prompt[index] = padToken
        prefix.copyInto(prompt, destinationOffset = 0)
        for (frame in 0 until 108) {
            val position = prefix.size + frame
            prompt[position] = semanticBegin + encoded[frame]
            for (book in 0 until 10) {
                prompt[(book + 1) * PREFILL_LENGTH + position] = encoded[book * 108 + frame]
            }
        }
        suffix.copyInto(prompt, destinationOffset = prefix.size + 108)

        val slowCaches = Array(48) { bytes(SLOW_CACHE_BYTES) }
        var slowLogits = FloatArray(0)
        var slowHidden = FloatArray(0)
        timed("slow_prefill") {
            val inputs = mutableMapOf<String, Any>(
                "input_ids" to longs(prompt),
                "input_pos" to ints(IntArray(PREFILL_LENGTH) { min(it, promptLength - 1) }),
                "cache_mask" to bools(BooleanArray(CACHE_LENGTH) { it < promptLength }),
                "last_index" to ints(intArrayOf(promptLength - 1)),
            )
            addSlowCaches(inputs, slowCaches)
            val logitsBuffer = bytes(4 * SLOW_LOGITS)
            val hiddenBuffer = bytes(4 * HIDDEN_SIZE)
            val outputs = mutableMapOf<String, Any>(
                "logits" to logitsBuffer,
                "fast_hidden" to hiddenBuffer,
            )
            addSlowCaches(outputs, slowCaches)
            interpreter("audio8_slow_ar_prefill256_int4.tflite").use { model ->
                model.runSignature(inputs, outputs, SIGNATURE)
            }
            slowLogits = readFloats(logitsBuffer, SLOW_LOGITS)
            slowHidden = readFloats(hiddenBuffer, HIDDEN_SIZE)
        }

        val generated = Array(10) { LongArray(MAX_FRAMES) }
        var frameCount = 0
        val decodeModel = interpreter("audio8_slow_ar_decode_int4.tflite")
        val fastModel = interpreter("audio8_fast_ar_kv_int4.tflite")
        try {
            timed("autoregressive_generation") {
                var cacheA = slowCaches
                var cacheB = Array(48) { bytes(SLOW_CACHE_BYTES) }
                while (frameCount < MAX_FRAMES) {
                    val semanticIndex = argmax(slowLogits)
                    if (semanticIndex == EOS_INDEX) break
                    val semanticToken = semanticBegin + semanticIndex
                    val frame = fastFrame(fastModel, slowHidden, semanticIndex.toLong())
                    for (book in 0 until 10) generated[book][frameCount] = frame[book]

                    val position = promptLength + frameCount
                    val inputIds = LongArray(11)
                    inputIds[0] = semanticToken.toLong()
                    for (book in 0 until 10) inputIds[book + 1] = frame[book]
                    val inputs = mutableMapOf<String, Any>(
                        "input_ids" to longs(inputIds),
                        "input_pos" to ints(intArrayOf(position)),
                        "cache_mask" to bools(BooleanArray(CACHE_LENGTH) { it <= position }),
                        "last_index" to ints(intArrayOf(0)),
                    )
                    addSlowCaches(inputs, cacheA)
                    val logitsBuffer = bytes(4 * SLOW_LOGITS)
                    val hiddenBuffer = bytes(4 * HIDDEN_SIZE)
                    val outputs = mutableMapOf<String, Any>(
                        "logits" to logitsBuffer,
                        "fast_hidden" to hiddenBuffer,
                    )
                    addSlowCaches(outputs, cacheB)
                    decodeModel.runSignature(inputs, outputs, SIGNATURE)
                    slowLogits = readFloats(logitsBuffer, SLOW_LOGITS)
                    slowHidden = readFloats(hiddenBuffer, HIDDEN_SIZE)
                    val swap = cacheA
                    cacheA = cacheB
                    cacheB = swap
                    frameCount++
                    if (frameCount % 8 == 0) report("generated_frames=$frameCount")
                }
            }
        } finally {
            fastModel.close()
            decodeModel.close()
        }
        check(frameCount > 0) { "Audio8 generated no codec frames" }

        val pcm = timed("codec_decoder") { decode(generated, frameCount) }
        writeWav(outputWav, pcm, SAMPLE_RATE)
        val seconds = pcm.size.toDouble() / SAMPLE_RATE
        report("PASS frames=$frameCount audio_seconds=${"%.2f".format(seconds)} wav=${outputWav.absolutePath}")
    }

    private fun fastFrame(model: Interpreter, hidden: FloatArray, semantic: Long): LongArray {
        var cacheA = Array(8) { bytes(FAST_CACHE_BYTES) }
        var cacheB = Array(8) { bytes(FAST_CACHE_BYTES) }
        val result = LongArray(10)
        result[0] = semantic
        var token = 0L
        for (position in 0 until 10) {
            if (position > 0) token = result[position - 1]
            val inputs = mutableMapOf<String, Any>(
                "slow_hidden" to floats(hidden),
                "token_id" to longs(longArrayOf(token)),
                "use_slow_hidden" to bools(booleanArrayOf(position == 0)),
                "input_pos" to ints(intArrayOf(position)),
            )
            addFastCaches(inputs, cacheA)
            val logits = bytes(4 * FAST_LOGITS)
            val outputs = mutableMapOf<String, Any>("logits" to logits)
            addFastCaches(outputs, cacheB)
            model.runSignature(inputs, outputs, SIGNATURE)
            if (position > 0) {
                result[position] = argmax(readFloats(logits, FAST_LOGITS)).toLong()
            }
            val swap = cacheA
            cacheA = cacheB
            cacheB = swap
        }
        return result
    }

    private fun decode(codes: Array<LongArray>, frames: Int): ShortArray {
        val pcm = ShortArray(frames * HOP_LENGTH)
        interpreter("audio8_codec_decoder_12f.tflite").use { decoder ->
            var start = 0
            while (start < frames) {
                val contextStart = max(0, start - STREAM_CONTEXT)
                val newCount = min(STREAM_CHUNK, frames - start)
                val available = start + newCount - contextStart
                val packed = LongArray(10 * DECODER_FRAMES)
                for (book in 0 until 10) {
                    for (index in 0 until DECODER_FRAMES) {
                        val source = min(contextStart + index, start + newCount - 1)
                        packed[book * DECODER_FRAMES + index] = codes[book][source]
                    }
                }
                val waveform = bytes(4 * DECODER_SAMPLES)
                decoder.runSignature(
                    mapOf("args_0" to longs(packed)),
                    mapOf("output_0" to waveform),
                    SIGNATURE,
                )
                val decoded = readFloats(waveform, DECODER_SAMPLES)
                val crop = (start - contextStart) * HOP_LENGTH
                for (sample in 0 until newCount * HOP_LENGTH) {
                    pcm[start * HOP_LENGTH + sample] =
                        (decoded[crop + sample].coerceIn(-1f, 1f) * Short.MAX_VALUE)
                            .toInt()
                            .toShort()
                }
                report("decoded_frames=${start + newCount}/$frames window=$available")
                start += newCount
            }
        }
        return pcm
    }

    private fun addSlowCaches(map: MutableMap<String, Any>, caches: Array<ByteBuffer>) {
        for (layer in 0 until 24) {
            map["kv_cache_k_$layer"] = caches[layer * 2].rewound()
            map["kv_cache_v_$layer"] = caches[layer * 2 + 1].rewound()
        }
    }

    private fun addFastCaches(map: MutableMap<String, Any>, caches: Array<ByteBuffer>) {
        for (layer in 0 until 4) {
            map["kv_cache_k_$layer"] = caches[layer * 2].rewound()
            map["kv_cache_v_$layer"] = caches[layer * 2 + 1].rewound()
        }
    }

    private fun interpreter(name: String): Interpreter {
        val file = File(models, name)
        check(file.isFile) { "Missing model ${file.absolutePath}" }
        return Interpreter(
            file,
            Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true),
        )
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return block().also {
            val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
            report("$label seconds=${"%.3f".format(seconds)}")
        }
    }

    private fun requiredFixture(name: String): File = File(fixture, name).also {
        check(it.isFile) { "Missing fixture ${it.absolutePath}" }
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (index in 1 until values.size) {
            if (values[index] > values[best]) best = index
        }
        return best
    }

    private fun bytes(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    private fun direct(value: ByteArray): ByteBuffer =
        bytes(value.size).apply { put(value); rewind() }

    private fun ints(value: IntArray): ByteBuffer =
        bytes(value.size * 4).apply { asIntBuffer().put(value); rewind() }

    private fun longs(value: LongArray): ByteBuffer =
        bytes(value.size * 8).apply { asLongBuffer().put(value); rewind() }

    private fun floats(value: FloatArray): ByteBuffer =
        bytes(value.size * 4).apply { asFloatBuffer().put(value); rewind() }

    private fun bools(value: BooleanArray): ByteBuffer = bytes(value.size).apply {
        value.forEach { put((if (it) 1 else 0).toByte()) }
        rewind()
    }

    private fun ByteBuffer.rewound(): ByteBuffer = apply { rewind() }

    private fun readLongs(file: File): LongArray =
        readLongs(direct(file.readBytes()), (file.length() / 8).toInt())

    private fun readLongs(buffer: ByteBuffer, count: Int): LongArray =
        LongArray(count).also {
            buffer.rewind()
            buffer.asLongBuffer().get(it)
        }

    private fun readFloats(buffer: ByteBuffer, count: Int): FloatArray =
        FloatArray(count).also {
            buffer.rewind()
            buffer.asFloatBuffer().get(it)
        }

    private fun writeWav(file: File, samples: ShortArray, rate: Int) {
        FileOutputStream(file).use { out ->
            val dataBytes = samples.size * 2
            fun int(value: Int) = out.write(
                byteArrayOf(
                    value.toByte(),
                    (value shr 8).toByte(),
                    (value shr 16).toByte(),
                    (value shr 24).toByte(),
                )
            )
            fun short(value: Int) =
                out.write(byteArrayOf(value.toByte(), (value shr 8).toByte()))
            out.write("RIFF".toByteArray())
            int(36 + dataBytes)
            out.write("WAVEfmt ".toByteArray())
            int(16)
            short(1)
            short(1)
            int(rate)
            int(rate * 2)
            short(2)
            short(16)
            out.write("data".toByteArray())
            int(dataBytes)
            samples.forEach { short(it.toInt()) }
        }
    }

    private companion object {
        const val SIGNATURE = "serving_default"
        const val PREFILL_LENGTH = 256
        const val CACHE_LENGTH = 512
        const val SLOW_CACHE_BYTES = 512 * 2 * 64 * 4
        const val FAST_CACHE_BYTES = 10 * 2 * 64 * 4
        const val HIDDEN_SIZE = 896
        const val SLOW_LOGITS = 4097
        const val FAST_LOGITS = 4096
        const val EOS_INDEX = 4096
        const val MAX_FRAMES = 64
        const val DECODER_FRAMES = 12
        const val DECODER_SAMPLES = 24_576
        const val HOP_LENGTH = 2_048
        const val STREAM_CONTEXT = 4
        const val STREAM_CHUNK = 8
        const val SAMPLE_RATE = 44_100
    }
}
