package dev.triplex.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePromptProgressTest {

    @Test
    fun `guide starts on first word and rejects negative time`() {
        val atStart = voicePromptProgress(0)
        val beforeStart = voicePromptProgress(-1_000)

        assertEquals(0, atStart.activeWordIndex)
        assertEquals(0, atStart.completedWordCount)
        assertEquals(0f, atStart.overallProgress, 0f)
        assertEquals(atStart, beforeStart)
    }

    @Test
    fun `guide advances monotonically through the consent statement`() {
        val samples = (0L..14_000L step 250L).map(::voicePromptProgress)

        assertTrue(samples.zipWithNext().all { (first, second) ->
            second.activeWordIndex >= first.activeWordIndex &&
                second.completedWordCount >= first.completedWordCount &&
                second.overallProgress >= first.overallProgress
        })
    }

    @Test
    fun `guide completes and remains bounded after its pacing window`() {
        val complete = voicePromptProgress(60_000)

        assertEquals(complete.wordCount - 1, complete.activeWordIndex)
        assertEquals(complete.wordCount, complete.completedWordCount)
        assertEquals(1f, complete.activeWordFraction, 0f)
        assertEquals(1f, complete.overallProgress, 0f)
    }
}
