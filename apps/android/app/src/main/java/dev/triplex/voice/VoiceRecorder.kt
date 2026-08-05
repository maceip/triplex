package dev.triplex.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the consent statement the user reads aloud. That recording is
 * simultaneously the consent record and the voice-cloning reference sample,
 * so there is exactly one artifact to keep, show, and revoke.
 *
 * Captures 24 kHz mono 16-bit PCM into a canonical WAV: the clone model
 * wants full-band speech, unlike the 16 kHz telephony path.
 */
@Singleton
class VoiceRecorder @Inject constructor() {

    private val recording = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = recording.get()

    /**
     * Records until [stop] is called, conditions the capture through
     * [ReferenceAudio], and writes the result to [target] when it is usable.
     *
     * Captures into memory first (bounded by [MAX_SECONDS]) because the
     * conditioning chain — DC removal, high-pass, silence trim, normalization
     * — needs the whole utterance before it can set a gain or judge quality.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(target: File): ReferenceAudio.Result = withContext(Dispatchers.IO) {
        check(recording.compareAndSet(false, true)) { "Already recording" }
        liveRms = 0.0
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioRecord unavailable (min buffer $minBuffer)" }
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE) // >= ~0.5 s of headroom
        val readChunkSamples = SAMPLE_RATE * METER_FRAME_MILLIS / 1_000

        // VOICE_RECOGNITION applies the platform's speech-tuned capture path
        // (noise suppression without the aggressive dynamics of VOICE_COMMUNICATION,
        // which is built for duplex calls and colours the voice we want to clone).
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        recorder = audioRecord
        val effects = attachEffects(audioRecord.audioSessionId)
        val capSamples = (MAX_SECONDS * SAMPLE_RATE).toInt()
        val captured = ShortArray(capSamples)
        var sampleCount = 0
        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize"
            }
            audioRecord.startRecording()
            // Keep generous AudioRecord headroom while reading bounded 20 ms
            // frames. The UI meter stays responsive, while the final sample
            // still passes through the whole-utterance conditioning pipeline.
            val chunk = ShortArray(readChunkSamples)
            while (recording.get() && sampleCount < capSamples) {
                val room = minOf(chunk.size, capSamples - sampleCount)
                val read = audioRecord.read(chunk, 0, room)
                if (read > 0) {
                    chunk.copyInto(captured, sampleCount, 0, read)
                    sampleCount += read
                    var energy = 0.0
                    for (index in 0 until read) {
                        val sample = chunk[index].toDouble()
                        energy += sample * sample
                    }
                    liveRms = kotlin.math.sqrt(energy / read)
                    if (sampleCount >= capSamples) {
                        Timber.i("Reference duration cap reached (%.0fs)", MAX_SECONDS)
                        recording.set(false)
                    }
                } else if (read < 0) {
                    Timber.e("AudioRecord.read error %d", read)
                    break
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            effects.forEach { effect -> runCatching { effect.release() } }
            recorder = null
            recording.set(false)
            liveRms = 0.0
        }

        val raw = captured.copyOf(sampleCount)
        val result = ReferenceAudio.prepare(raw, SAMPLE_RATE)
        when (result) {
            is ReferenceAudio.Result.Usable -> {
                writeWav(target, result.pcm)
                Timber.i(
                    "Reference accepted: %.1fs speech=%.0f%% SNR=%.1fdB rms=%.0f",
                    result.quality.durationSeconds,
                    result.quality.speechRatio * 100,
                    result.quality.snrDb,
                    result.quality.rmsAfterGain
                )
            }
            is ReferenceAudio.Result.Unusable -> Timber.w(
                "Reference rejected (%s): %.1fs speech=%.0f%% SNR=%.1fdB clipped=%.2f%%",
                result.reason,
                result.quality.durationSeconds,
                result.quality.speechRatio * 100,
                result.quality.snrDb,
                result.quality.clippedRatio * 100
            )
        }
        result
    }

    fun stop() {
        recording.set(false)
    }

    /** Current raw capture energy for UI metering; never alters prepared PCM. */
    @Volatile
    var liveRms: Double = 0.0
        private set

    /**
     * Enables the device's own noise suppressor and gain control when it has
     * them. Availability is per-device, so this is best-effort; the software
     * conditioning in [ReferenceAudio] is what guarantees a usable level.
     */
    private fun attachEffects(sessionId: Int): List<AudioEffect> {
        val effects = mutableListOf<AudioEffect>()
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId) }
                .getOrNull()
                ?.also { it.enabled = true; effects += it }
                ?: Timber.w("NoiseSuppressor unavailable on this device")
        }
        if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(sessionId) }
                .getOrNull()
                ?.also { it.enabled = true; effects += it }
        }
        Timber.i("Capture effects enabled: %d", effects.size)
        return effects
    }

    /** Writes conditioned PCM as a canonical mono 16-bit WAV. */
    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                    // PCM chunk size
        header.putShort(1)                   // PCM format
        header.putShort(1)                   // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE) // byte rate
        header.putShort(BYTES_PER_SAMPLE.toShort())   // block align
        header.putShort(16)                  // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes)

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) body.putShort(sample)

        file.outputStream().use { output ->
            output.write(header.array())
            output.write(body.array())
        }
    }

    /** Plays a WAV file to the device speaker; suspends until complete. */
    suspend fun play(file: File) = withContext(Dispatchers.IO) {
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            while (player.isPlaying) {
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Timber.e(e, "Playback failed for %s", file.name)
        } finally {
            player.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_BYTES = 44
        private const val METER_FRAME_MILLIS = 20
        const val MIN_SECONDS = 3f
        // Below the model's 30 s ceiling: long references cost far more to
        // prepare without improving the clone.
        const val MAX_SECONDS = 15f
    }
}
