package com.triplex.dialer.triplex

/**
 * Block-rendering sine oscillator — no allocations in the hot loop.
 *
 * Architecture from the recommendation:
 * - Caller owns and reuses [output] FloatArray
 * - Parameters passed once per block so the rendering loop does not
 *   repeatedly read StateFlow, volatile properties, or synchronized state
 * - Double phase accumulator (Kotlin's Float.sin() converts to Double anyway)
 * - Single Math.sin() call per sample, no wrappers, no JNI
 */
class SineOscillator(
    sampleRate: Int,
) {
    private val sampleRate = sampleRate.toDouble()
    private var phase = 0.0

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
    }

    /**
     * Render one block of sine wave into [output].
     *
     * @param output  Preallocated FloatArray, reused across calls
     * @param offset  Starting index in [output]
     * @param frames  Number of frames to render
     * @param frequencyHz  Oscillator frequency in Hz
     * @param gain   Linear gain (0.0 – 1.0)
     */
    fun render(
        output: FloatArray,
        offset: Int,
        frames: Int,
        frequencyHz: Double,
        gain: Float,
    ) {
        var localPhase = phase
        val phaseStep = TWO_PI * frequencyHz / sampleRate

        var index = offset
        val end = offset + frames

        while (index < end) {
            output[index] = (Math.sin(localPhase) * gain).toFloat()

            localPhase += phaseStep
            if (localPhase >= TWO_PI) {
                localPhase -= TWO_PI
            }

            index++
        }

        phase = localPhase
    }

    /** Reset phase to zero (e.g. on new note / utterance start). */
    fun reset() {
        phase = 0.0
    }
}
