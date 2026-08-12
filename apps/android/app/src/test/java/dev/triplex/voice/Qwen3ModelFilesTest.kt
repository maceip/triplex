package dev.triplex.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class Qwen3ModelFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun requiredBasenamesMatchArtifactList() {
        assertEquals(
            Qwen3ModelFiles.ARTIFACTS.map { it.first },
            Qwen3ModelFiles.REQUIRED_BASENAMES,
        )
        assertTrue(Qwen3ModelFiles.REQUIRED_BASENAMES.contains("speaker_encoder.tflite"))
        assertTrue(Qwen3ModelFiles.REQUIRED_BASENAMES.contains("talker_int4.tflite"))
    }

    @Test
    fun isCompleteRequiresEveryBasename() {
        val dir = tmp.newFolder("models")
        assertFalse(Qwen3ModelFiles.isComplete(dir))
        Qwen3ModelFiles.REQUIRED_BASENAMES.forEach { name ->
            File(dir, name).writeText("x")
        }
        assertTrue(Qwen3ModelFiles.isComplete(dir))
    }
}

class SpeakerEncoderWindowTest {
    @Test
    fun windowCoversEncoderMelFrames() {
        assertEquals(469 * 256, SpeakerEncoder.WINDOW_SAMPLES)
        assertEquals(SpeakerEncoder.WINDOW_SAMPLES / 2, SpeakerEncoder.WINDOW_HOP_SAMPLES)
    }
}
