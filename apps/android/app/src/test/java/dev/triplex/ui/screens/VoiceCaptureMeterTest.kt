package dev.triplex.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureMeterTest {

    @Test
    fun silenceAndInvalidReadingsStayAtRest() {
        assertEquals(0f, normalizeVoiceRms(Double.NaN), 0f)
        assertEquals(0f, normalizeVoiceRms(Double.POSITIVE_INFINITY), 0f)
        assertEquals(0f, normalizeVoiceRms(90.0), 0f)
    }

    @Test
    fun speechEnergyMapsSmoothlyAndClamps() {
        val quietSpeech = normalizeVoiceRms(250.0)
        val normalSpeech = normalizeVoiceRms(1_600.0)
        val strongSpeech = normalizeVoiceRms(6_090.0)

        assertTrue(quietSpeech in 0.1f..0.3f)
        assertTrue(normalSpeech > quietSpeech)
        assertEquals(1f, strongSpeech, 0.001f)
        assertEquals(1f, normalizeVoiceRms(50_000.0), 0f)
    }

    @Test
    fun durationProgressIsBoundedByCaptureLimit() {
        assertEquals(
            0.5f,
            VoiceCaptureMeter(elapsedMillis = 7_500, maximumSeconds = 15).durationProgress,
            0.001f
        )
        assertEquals(
            1f,
            VoiceCaptureMeter(elapsedMillis = 30_000, maximumSeconds = 15).durationProgress,
            0f
        )
    }
}
