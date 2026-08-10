package dev.triplex.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last thing between the model and the phone line.
 *
 * Every input below is output a small on-device model actually produces when
 * asked for one spoken sentence. None of them are hypothetical: they are the
 * shapes that get past prompting, and each one is audible if it reaches TTS —
 * the caller hears "asterisk asterisk", or a bullet character, or the model
 * introducing itself before answering.
 */
class SpokenReplyTest {

    @Test
    fun a_clean_reply_passes_through_untouched() {
        val clean = "Thanks — I can take a message. Who should I say is calling?"
        assertEquals(clean, SpokenReply.sanitize(clean))
    }

    @Test
    fun markdown_emphasis_never_reaches_the_speech_engine() {
        val cases = mapOf(
            "**Sure thing.** I can take a message." to "Sure thing. I can take a message.",
            "I can *definitely* help with that." to "I can definitely help with that.",
            "The order is `SG-4471`." to "The order is SG-4471.",
            "That is ~~not~~ correct." to "That is not correct.",
            "___Understood___, I will pass it on." to "Understood, I will pass it on.",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, SpokenReply.sanitize(raw), "input: $raw")
        }
    }

    @Test
    fun a_list_answer_becomes_its_first_item() {
        val raw = """
            Here are some options:
            - I can take a message.
            - I can ask them to call you back.
            - I can pass you to voicemail.
        """.trimIndent()

        assertEquals("I can take a message.", SpokenReply.sanitize(raw))
    }

    @Test
    fun numbered_lists_and_headings_are_handled_the_same_way() {
        assertEquals(
            "What time works for you?",
            SpokenReply.sanitize("## Reply\n\n1. What time works for you?\n2. Which time zone?"),
        )
    }

    @Test
    fun text_about_the_reply_is_dropped_rather_than_spoken() {
        val cases = mapOf(
            "Sure! Here's a reply: Thanks for calling, who is this?" to
                "Thanks for calling, who is this?",
            "Agent: I will pass that along." to "I will pass that along.",
            "Here is my response: Could you spell that for me?" to
                "Could you spell that for me?",
            "Assistant: What time suits you?" to "What time suits you?",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, SpokenReply.sanitize(raw), "input: $raw")
        }
    }

    /**
     * The sanitizer's job is to stop non-speech reaching the wire, not to edit
     * the agent's manner. "Of course," is how people answer the phone; removing
     * it buys two syllables and costs the agent its warmth.
     */
    @Test
    fun conversational_openers_are_left_alone() {
        val untouched = listOf(
            "Of course, I can help with the return.",
            "Sure, let me take that down.",
            "Certainly. Which day works best?",
            "Okay, I have that.",
        )
        for (line in untouched) {
            assertEquals(line, SpokenReply.sanitize(line), "input: $line")
        }
    }

    @Test
    fun stage_directions_are_removed_rather_than_read_aloud() {
        assertEquals(
            "I understand. Let me note that down.",
            SpokenReply.sanitize("(pause) I understand. [warm tone] Let me note that down."),
        )
    }

    /** Parentheses carry real spoken detail too, so they are not blanket-stripped. */
    @Test
    fun parenthesised_speech_survives_when_it_is_not_a_direction() {
        val line = "You can reach the desk on (415) 555 0123 after nine."
        assertEquals(line, SpokenReply.sanitize(line))
    }

    @Test
    fun surrounding_quotes_are_stripped() {
        assertEquals(
            "Could you repeat the order number?",
            SpokenReply.sanitize("\"Could you repeat the order number?\""),
        )
    }

    @Test
    fun a_rambling_answer_is_cut_to_two_sentences() {
        val raw = "I can help with that. What is the order number? " +
            "It is usually on the confirmation email. Sometimes it is on the invoice too."

        assertEquals(
            "I can help with that. What is the order number?",
            SpokenReply.sanitize(raw),
        )
    }

    /**
     * A hard character cut is the difference between "we can offer a
     * replacement" and the TTS engine saying "we can offer a replaceme".
     */
    @Test
    fun a_long_sentence_is_cut_at_a_word_boundary_and_terminated() {
        val raw = "The quantity of replacement units available in the regional " +
            ("warehouse today is limited and subject to confirmation and ".repeat(8))

        val spoken = assertNotNull(SpokenReply.sanitize(raw))
        assertTrue(spoken.length <= SpokenReply.MAX_CHARACTERS + 1, "length was ${spoken.length}")
        assertTrue(spoken.endsWith("."), "a cut utterance still ends like a sentence")
        // No half-words at the seam.
        assertFalse(spoken.dropLast(1).endsWith("limite"))
        assertTrue(raw.contains(spoken.dropLast(1).trim()), "the cut is a prefix of the original")
    }

    @Test
    fun output_with_nothing_speakable_in_it_is_rejected() {
        val unusable = listOf("", "   ", "\n\n", "**", "---", "* \n- \n", "###", "```\n```")
        for (raw in unusable) {
            assertNull(SpokenReply.sanitize(raw), "should be rejected: ${raw.replace("\n", "\\n")}")
        }
    }

    @Test
    fun a_multi_paragraph_answer_yields_only_the_first_paragraph() {
        val raw = """
            I can take a message about the invoice.

            If you would prefer, I can also ask them to call you back this afternoon,
            or send an email summary instead.
        """.trimIndent()

        assertEquals("I can take a message about the invoice.", SpokenReply.sanitize(raw))
    }

    /** Numbers and punctuation a phone call actually needs must survive. */
    @Test
    fun spoken_detail_is_preserved() {
        val raw = "Order SG-4471-2290, delivered on the 11th — is that right?"
        assertEquals(raw, SpokenReply.sanitize(raw))
    }
}
