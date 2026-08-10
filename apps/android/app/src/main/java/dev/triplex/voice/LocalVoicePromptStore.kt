package dev.triplex.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device persisted speaker prompt for cloned TTS.
 *
 * Source of truth is the 1024-d x-vector written by [SpeakerEncoder] after
 * multi-phrase enrollment. Gateway profiles are not consulted for readiness.
 */
@Singleton
class LocalVoicePromptStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File
        get() = File(context.filesDir, "voice_prompt").also { it.mkdirs() }

    val xVectorFile: File get() = File(root, "x_vector.npy")
    private val metaFile: File get() = File(root, "meta.json")

    data class Meta(
        val synthesisReady: Boolean = false,
        val phraseCount: Int = 0,
        val consentStatement: String = "",
        val verifiedAtEpochMs: Long = 0L,
        val placement: String = "LOCAL",
        val placementReason: String = "on-device Qwen3-TTS + ECAPA speaker encoder",
    )

    fun hasModels(): Boolean {
        val models = File(context.filesDir, "models/qwen3-tts")
        return REQUIRED_MODELS.all { File(models, it).isFile }
    }

    fun readXVector(): FloatArray? {
        val file = xVectorFile
        if (!file.isFile) return null
        return runCatching { readFloat32Npy(file) }
            .onFailure { Timber.w(it, "Failed to read x_vector.npy") }
            .getOrNull()
            ?.takeIf { it.size == 1024 }
    }

    fun writeXVector(embedding: FloatArray) {
        require(embedding.size == 1024) { "x-vector must be 1024-d" }
        writeFloat32Npy(xVectorFile, embedding)
    }

    fun readMeta(): Meta {
        if (!metaFile.isFile) return Meta()
        return runCatching {
            val json = JSONObject(metaFile.readText())
            Meta(
                synthesisReady = json.optBoolean("synthesis_ready", false),
                phraseCount = json.optInt("phrase_count", 0),
                consentStatement = json.optString("consent_statement", ""),
                verifiedAtEpochMs = json.optLong("verified_at_epoch_ms", 0L),
                placement = json.optString("placement", "LOCAL"),
                placementReason = json.optString(
                    "placement_reason",
                    "on-device Qwen3-TTS + ECAPA speaker encoder",
                ),
            )
        }.getOrDefault(Meta())
    }

    fun writeMeta(meta: Meta) {
        metaFile.writeText(
            JSONObject()
                .put("synthesis_ready", meta.synthesisReady)
                .put("phrase_count", meta.phraseCount)
                .put("consent_statement", meta.consentStatement)
                .put("verified_at_epoch_ms", meta.verifiedAtEpochMs)
                .put("placement", meta.placement)
                .put("placement_reason", meta.placementReason)
                .toString(2) + "\n",
        )
    }

    fun revoke() {
        xVectorFile.delete()
        metaFile.delete()
        root.listFiles()?.forEach { child ->
            if (child.name.startsWith("phrase_")) child.delete()
        }
    }

    fun synthesisReady(): Boolean {
        val meta = readMeta()
        return meta.synthesisReady && readXVector() != null && hasModels()
    }

    fun modelsDir(): File = File(context.filesDir, "models/qwen3-tts")

    private fun writeFloat32Npy(file: File, data: FloatArray) {
        val headerDict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${data.size},), }"
        var header = Magic + "\u0001\u0000" // version 1.0 placeholder; rewritten below
        // Build proper npy v1 header
        val dictBytes = (headerDict + " ".repeat(16)).toByteArray(Charsets.US_ASCII)
        // pad so header_len + 10 is divisible by 64
        val preamble = 10
        var headerLen = dictBytes.size
        val pad = (64 - ((preamble + headerLen) % 64)) % 64
        val padded = dictBytes + ByteArray(pad) { if (it == pad - 1) '\n'.code.toByte() else ' '.code.toByte() }
        headerLen = padded.size
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray())
            out.write(byteArrayOf(1, 0))
            out.write(byteArrayOf((headerLen and 0xff).toByte(), ((headerLen shr 8) and 0xff).toByte()))
            out.write(padded)
            val buf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            data.forEach { buf.putFloat(it) }
            out.write(buf.array())
        }
    }

    private fun readFloat32Npy(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 10 && bytes[0] == 0x93.toByte()) { "not npy" }
        val headerLen = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
        val dataOffset = 10 + headerLen
        val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    private companion object {
        const val Magic = "" // unused; written explicitly above
        val REQUIRED_MODELS = listOf(
            "talker_int4.tflite",
            "mtp_folded_int8.tflite",
            "codec_partA.tflite",
            "codec_partB.tflite",
            "vocab.json",
            "merges.txt",
            "codec_embedding_fp32.npy",
            "mtp_embeddings_fp16.npy",
            "text_embedding_fp16.npy",
            "text_projection_fp32.npz",
            "speaker_encoder.tflite",
            "mel_basis_slaney_128.npy",
        )
    }
}
