package dev.triplex.voice

import dev.triplex.data.remote.VoiceProfileStatus
import dev.triplex.data.repository.Result
import dev.triplex.speech.tts.Qwen3CallVoice
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only voice-clone lifecycle: consented multi-phrase capture, on-device
 * ECAPA embedding, local verify via Qwen3 LiteRT, preview, revoke.
 *
 * Placement is always LOCAL. Gateway voice endpoints are not used.
 */
@Singleton
class VoiceCloneRepository @Inject constructor(
    private val promptStore: LocalVoicePromptStore,
    private val speakerEncoder: SpeakerEncoder,
    private val qwenVoice: Qwen3CallVoice,
) {
    suspend fun status(): VoiceProfileStatus? {
        val meta = promptStore.readMeta()
        val ready = promptStore.synthesisReady()
        if (!meta.synthesisReady && !ready && promptStore.readXVector() == null) {
            return VoiceProfileStatus()
        }
        return VoiceProfileStatus(
            profile_id = if (ready) "local" else null,
            synthesis_ready = ready,
            placement = meta.placement,
            placement_reason = meta.placementReason,
            reference_seconds = null,
            consent_recorded_at = null,
        )
    }

    /**
     * Encodes one or more conditioned 24 kHz reference WAVs, writes the
     * centroid x-vector, and verifies synthesis locally.
     */
    suspend fun prepareFromPhrases(
        phraseWavs: List<File>,
        consentStatement: String,
    ): Result<VoiceProfileStatus> {
        if (!promptStore.hasModels()) {
            return Result.Error(
                "On-device voice models are not installed. Push the Qwen3 LiteRT bundle first.",
            )
        }
        if (phraseWavs.isEmpty()) {
            return Result.Error("No reference recordings to enroll.")
        }
        return try {
            val embeddings = phraseWavs.map { wav ->
                val pcm = readMonoPcm16(wav)
                speakerEncoder.encode(pcm)
            }
            val centroid = speakerEncoder.centroid(embeddings)
            promptStore.writeXVector(centroid)

            // Local verify: must produce audible PCM within a bounded attempt.
            val preview = File(promptStore.modelsDir().parentFile, "verify_preview.wav")
            val verify = preview(VERIFY_TEXT, preview)
            if (verify is Result.Error) {
                promptStore.revoke()
                return Result.Error(
                    "that recording did not produce a usable voice; please record again",
                    verify.exception,
                )
            }
            val meta = LocalVoicePromptStore.Meta(
                synthesisReady = true,
                phraseCount = phraseWavs.size,
                consentStatement = consentStatement,
                verifiedAtEpochMs = System.currentTimeMillis(),
                placement = "LOCAL",
                placementReason = "on-device Qwen3-TTS + ECAPA speaker encoder",
            )
            promptStore.writeMeta(meta)
            Timber.i("Local voice profile ready (%d phrases)", phraseWavs.size)
            Result.Success(
                VoiceProfileStatus(
                    profile_id = "local",
                    synthesis_ready = true,
                    placement = meta.placement,
                    placement_reason = meta.placementReason,
                ),
            )
        } catch (e: Exception) {
            Timber.e(e, "Local voice preparation failed")
            promptStore.revoke()
            Result.Error("Could not prepare voice: ${e.message}", e)
        }
    }

    /** Synthesizes [text] in the cloned voice and writes a WAV to [target]. */
    suspend fun preview(text: String, target: File): Result<File> {
        val spk = promptStore.readXVector()
            ?: return Result.Error("No prepared voice profile")
        return try {
            val chunks = ArrayList<ShortArray>()
            qwenVoice.synthesizeStream(text).collect { chunk ->
                chunks += chunk
            }
            val total = chunks.sumOf { it.size }
            if (total == 0) return Result.Error("Preview produced no audio")
            val pcm = ShortArray(total)
            var off = 0
            for (c in chunks) {
                c.copyInto(pcm, off)
                off += c.size
            }
            writeWav16k(target, pcm)
            Result.Success(target)
        } catch (e: Exception) {
            Timber.e(e, "Local voice preview failed")
            Result.Error("Preview failed: ${e.message}", e)
        }
    }

    suspend fun revoke(): Result<Unit> {
        qwenVoice.cancel()
        promptStore.revoke()
        Timber.i("Local voice profile revoked")
        return Result.Success(Unit)
    }

    private fun readMonoPcm16(wav: File): ShortArray {
        val bytes = wav.readBytes()
        require(bytes.size > 44) { "WAV too short" }
        // Canonical PCM WAV from VoiceRecorder: 44-byte header, 16-bit mono.
        val dataSize = bytes.size - 44
        val samples = dataSize / 2
        val buf = ByteBuffer.wrap(bytes, 44, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(samples) { buf.short }
    }

    private fun writeWav16k(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(16_000)
        header.putInt(16_000 * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { body.putShort(it) }
            out.write(body.array())
        }
    }

    private companion object {
        const val VERIFY_TEXT = "Testing my new voice."
    }
}
