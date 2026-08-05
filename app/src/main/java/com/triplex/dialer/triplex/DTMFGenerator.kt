package com.triplex.dialer.triplex

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates DTMF (Dual-Tone Multi-Frequency) tones for automated keypress
 * during phone calls to businesses.
 *
 * Each DTMF digit is two sine waves summed — one from the low band (697-941 Hz)
 * and one from the high band (1209-1633 Hz).
 *
 * Generates 16-bit PCM samples at 16 kHz for the given digit + duration.
 */
object DTMFGenerator {

    /** DTMF digit → (low freq, high freq) mapping. */
    private val DTMF_TONES: Map<Char, Pair<Double, Double>> = mapOf(
        '1' to Pair(697.0, 1209.0),
        '2' to Pair(697.0, 1336.0),
        '3' to Pair(697.0, 1477.0),
        '4' to Pair(770.0, 1209.0),
        '5' to Pair(770.0, 1336.0),
        '6' to Pair(770.0, 1477.0),
        '7' to Pair(852.0, 1209.0),
        '8' to Pair(852.0, 1336.0),
        '9' to Pair(852.0, 1477.0),
        '0' to Pair(941.0, 1336.0),
        '*' to Pair(941.0, 1209.0),
        '#' to Pair(941.0, 1477.0),
    )

    /** Generate PCM samples for a single DTMF digit. */
    fun generateTone(digit: Char, durationMs: Int = 200, sampleRate: Int = 16000): ShortArray {
        val freqs = DTMF_TONES[digit] ?: return ShortArray(0)
        val numSamples = (sampleRate * durationMs / 1000).coerceAtMost(sampleRate)
        val samples = ShortArray(numSamples)

        val lowFreq = freqs.first
        val highFreq = freqs.second
        val amplitude = 0.3  // -10 dB below full scale to avoid distortion

        for (i in samples.indices) {
            val t = i.toDouble() / sampleRate
            val low = sin(2.0 * PI * lowFreq * t)
            val high = sin(2.0 * PI * highFreq * t)
            val sample = (low + high) * amplitude * 32767.0
            samples[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }

        return samples
    }

    /** Generate PCM samples for a sequence of DTMF digits (e.g., "123"). */
    fun generateSequence(digits: String, durationMs: Int = 200, gapMs: Int = 100, sampleRate: Int = 16000): ShortArray {
        if (digits.isEmpty()) return ShortArray(0)

        val totalSamples = (sampleRate * (digits.length * (durationMs + gapMs)) / 1000).coerceAtMost(sampleRate * 10)
        val buffer = ShortArray(totalSamples)
        var offset = 0

        for (ch in digits) {
            val tone = generateTone(ch, durationMs, sampleRate)
            tone.copyInto(buffer, offset)
            offset += tone.size
            // Gap of silence
            offset += (sampleRate * gapMs / 1000)
        }

        return buffer.copyOf(offset.coerceAtMost(totalSamples))
    }
}
