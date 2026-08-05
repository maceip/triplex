package dev.triplex.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reference-recording conditioning and quality gate.
 *
 * A phone captured through the air sits far below a clean file: measured on a
 * Pixel 10 Pro Fold, mic capture came in ~10 dB quieter with ~38 dB worse SNR
 * than a studio-clean reference, and references like that can push the clone
 * model into a generation that never converges. Rather than spend expensive
 * model time on audio that cannot produce a good voice, condition it here and
 * reject what is still unusable, with feedback the user can act on.
 *
 * Deliberately DSP-light and dependency-free: DC removal, a speech high-pass,
 * noise-gated trimming, and peak-safe RMS normalization (the AGC). Aggressive
 * spectral denoising would colour the voice we are trying to clone.
 */
object ReferenceAudio {

    /** Target RMS for normalization; matches known-good clean references. */
    const val TARGET_RMS = 1700.0
    /** Leave headroom so normalization cannot clip. */
    const val PEAK_CEILING = 29_000.0
    const val MIN_SNR_DB = 12.0
    const val MIN_SPEECH_RATIO = 0.25
    const val MAX_CLIPPED_RATIO = 0.005
    private const val CLIP_THRESHOLD = 32_000

    /**
     * Absolute floor for the pre-gain speech level. Relative measures alone
     * are not enough: a device noise suppressor drives the room to near
     * digital silence, which makes SNR look enormous and lets a recording of
     * nothing pass. Someone actually speaking at arm's length lands well
     * above this on the 16-bit scale.
     */
    const val MIN_SPEECH_LEVEL = 200f
    /**
     * Refuse to amplify beyond this. Needing more gain than this means the
     * source was too quiet to carry a voice, and boosting only raises noise.
     */
    const val MAX_GAIN = 8f

    data class Quality(
        val durationSeconds: Float,
        val speechRatio: Float,
        val snrDb: Float,
        val clippedRatio: Float,
        val rmsAfterGain: Float,
        /** Pre-gain 90th-percentile frame level; the absolute loudness check. */
        val speechLevel: Float = 0f,
        val appliedGain: Float = 1f
    )

    sealed class Result {
        data class Usable(val pcm: ShortArray, val quality: Quality) : Result()
        /** [reason] is user-facing and tells them what to change. */
        data class Unusable(val reason: String, val quality: Quality) : Result()
    }

    /**
     * Conditions raw capture and judges whether it can produce a voice.
     * Returns the processed PCM ready for upload, or an actionable reason.
     */
    fun prepare(pcm: ShortArray, sampleRate: Int): Result {
        if (pcm.isEmpty()) {
            return Result.Unusable(
                "Nothing was recorded. Try again.",
                Quality(0f, 0f, 0f, 0f, 0f)
            )
        }

        val clippedRatio = pcm.count { abs(it.toInt()) >= CLIP_THRESHOLD }.toFloat() / pcm.size

        var samples = FloatArray(pcm.size) { pcm[it].toFloat() }
        removeDcOffset(samples)
        samples = highPass(samples, sampleRate)

        val frame = max(1, sampleRate / 100) // 10 ms
        val energies = frameRms(samples, frame)
        val noiseFloor = percentile(energies, 10f)
        val speechLevel = percentile(energies, 90f)
        val snrDb = (
            20.0 * log10(
                max(speechLevel.toDouble(), 1e-6) / max(noiseFloor.toDouble(), 1e-6)
            )
            ).toFloat()

        // Speech frames sit meaningfully above the room, not just above zero.
        val speechThreshold = max(noiseFloor * 3f, speechLevel * 0.15f)
        val speechRatio = energies.count { it >= speechThreshold }.toFloat() /
            max(1, energies.size)

        val trimmed = trimToSpeech(samples, energies, frame, speechThreshold)
        val trimmedRms = rms(trimmed)
        val gain = gainFor(trimmed, trimmedRms)
        val gained = FloatArray(trimmed.size) { trimmed[it] * gain }
        val finalRms = rms(gained)
        val duration = trimmed.size.toFloat() / sampleRate

        val quality = Quality(
            durationSeconds = duration,
            speechRatio = speechRatio,
            snrDb = snrDb,
            clippedRatio = clippedRatio,
            rmsAfterGain = finalRms,
            speechLevel = speechLevel,
            appliedGain = gain
        )

        val reason = when {
            clippedRatio > MAX_CLIPPED_RATIO ->
                "That was too loud and distorted. Hold the phone further away and try again."
            // Absolute loudness first: it is the check a noise suppressor
            // cannot fake, unlike SNR and the relative speech ratio.
            speechLevel < MIN_SPEECH_LEVEL || gain > MAX_GAIN ->
                "We could barely hear you. Speak up and hold the phone closer, then try again."
            speechRatio < MIN_SPEECH_RATIO ->
                "We mostly heard silence. Read the whole sentence without long pauses."
            snrDb < MIN_SNR_DB ->
                "It's too noisy where you are. Move somewhere quieter and try again."
            duration < VoiceRecorder.MIN_SECONDS ->
                "Too short once silence was trimmed. Read the whole sentence."
            else -> null
        }
        if (reason != null) {
            return Result.Unusable(reason, quality)
        }

        val out = ShortArray(gained.size) {
            gained[it].coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return Result.Usable(out, quality)
    }

    private fun removeDcOffset(samples: FloatArray) {
        var sum = 0.0
        for (value in samples) sum += value
        val mean = (sum / samples.size).toFloat()
        for (i in samples.indices) samples[i] -= mean
    }

    /** One-pole high-pass near 80 Hz: drops rumble and handling noise. */
    private fun highPass(samples: FloatArray, sampleRate: Int): FloatArray {
        val rc = 1.0f / (2f * Math.PI.toFloat() * 80f)
        val dt = 1.0f / sampleRate
        val alpha = rc / (rc + dt)
        val out = FloatArray(samples.size)
        var previousIn = samples[0]
        var previousOut = 0f
        for (i in samples.indices) {
            val current = samples[i]
            previousOut = alpha * (previousOut + current - previousIn)
            out[i] = previousOut
            previousIn = current
        }
        return out
    }

    private fun frameRms(samples: FloatArray, frame: Int): FloatArray {
        val count = samples.size / frame
        return FloatArray(count) { index ->
            var sum = 0.0
            val start = index * frame
            for (i in start until start + frame) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / frame).toFloat()
        }
    }

    private fun percentile(values: FloatArray, percent: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val index = ((percent / 100f) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Keeps speech plus a short margin, dropping dead air at both ends. */
    private fun trimToSpeech(
        samples: FloatArray,
        energies: FloatArray,
        frame: Int,
        threshold: Float
    ): FloatArray {
        val first = energies.indexOfFirst { it >= threshold }
        val last = energies.indexOfLast { it >= threshold }
        if (first < 0 || last < first) return samples
        val margin = 10 // 100 ms
        val start = max(0, (first - margin) * frame)
        val end = min(samples.size, (last + 1 + margin) * frame)
        return samples.copyOfRange(start, end)
    }

    /** RMS-to-target gain, bounded by the peak ceiling — the AGC step. */
    private fun gainFor(samples: FloatArray, currentRms: Float): Float {
        if (currentRms <= 0f) return 1f
        var gain = (TARGET_RMS / currentRms).toFloat()
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0f) {
            gain = min(gain, (PEAK_CEILING / peak).toFloat())
        }
        return gain
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (value in samples) sum += value.toDouble() * value
        return sqrt(sum / samples.size).toFloat()
    }
}
