package dev.triplex.dialogue

/**
 * Turns raw model output into something safe to send to a text-to-speech
 * engine and out onto a phone line.
 *
 * A language model asked for one spoken sentence will still, sometimes, return
 * a bulleted list under a heading, a `**bold**` phrase, a `[warm tone]` stage
 * direction, a "Sure! Here's a reply:" preamble, or three paragraphs. Every one
 * of those is audible: the engine reads the asterisks, the caller hears
 * "asterisk asterisk", and the agent sounds broken. Prompting reduces the rate;
 * it does not make it zero, and a live call has no second chance.
 *
 * So the boundary is enforced here rather than hoped for in the prompt. This is
 * a pure function over a string, which means the messy real outputs collected
 * from the device are a table in a test rather than a bug report from a call.
 *
 * ## What it deliberately does not do
 *
 * It does not tidy up the agent's manner. "Of course, I can help with that" is
 * a person talking on the phone, and stripping the "Of course," to save two
 * syllables makes the agent curt for no gain. Only text that is *about* the
 * reply — headings, list scaffolding, meta-preambles, stage directions — is
 * removed, because only that text is wrong to say out loud.
 */
object SpokenReply {

    /** Two sentences is the target; the third is where models start rambling. */
    const val MAX_SENTENCES: Int = 2

    /**
     * Roughly 15 seconds of speech at a natural pace. Past this the caller has
     * stopped listening, and on an interrupted turn the unheard tail is wasted
     * synthesis.
     */
    const val MAX_CHARACTERS: Int = 320

    private val LIST_ITEM = Regex("""^(?:[-*+•]|\d+[.)])\s+(?=\S)""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val HORIZONTAL_RULE = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,}|`{3,}\w*)\s*$""")

    /**
     * Square brackets do not occur in speech, so anything short inside them is
     * scaffolding — a stage direction, an alternative, a placeholder — and goes.
     */
    private val BRACKETED = Regex("""\[[^\]]{0,60}]""")

    /**
     * Parentheses do occur in speech ("call me on (415) 555 0123"), so these
     * are removed only when they say how to perform the line rather than what
     * to say.
     */
    private val PARENTHETICAL_DIRECTION = Regex(
        """\(\s*[^)]{0,60}\b(?:pause|beat|laugh\w*|sigh\w*|smil\w*|warm\w*|gentl\w*|voice|tone|aside|note to self)\b[^)]{0,60}\)""",
        RegexOption.IGNORE_CASE,
    )

    private val MARKDOWN_EMPHASIS = Regex("""(?:\*{1,3}|_{2,3}|`+|~~)""")

    /**
     * Text about the reply rather than in it. The optional filler word is only
     * stripped when it introduces one of these — "Sure! Here's a reply:" is
     * scaffolding; a bare "Sure!" is an answer.
     */
    private val META_PREAMBLE = Regex(
        """^(?:(?:sure|certainly|absolutely|okay|ok|got it|of course)\s*[!,.:;—-]+\s*)?""" +
            """(?:here(?:'s| is)[^:\n]{0,40}:|(?:reply|response|answer|agent|assistant|triplex)\s*:)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("""\s+""")
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

    /**
     * @return the utterance to speak, or null when nothing speakable survived.
     *   Null means the model produced no usable reply and the caller must treat
     *   it as a reasoner failure — never as "say nothing and hope".
     */
    fun sanitize(raw: String): String? {
        val lines = raw
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !HORIZONTAL_RULE.matches(it) }
            .toList()

        // A model that answers with a list means the first item — not the
        // sentence introducing it, and not all six. Checked before headings so
        // "## Reply / 1. What time works?" yields the item, not the title.
        val listItems = lines.filter(LIST_ITEM::containsMatchIn)
        val candidates = when {
            listItems.isNotEmpty() -> listItems.map { LIST_ITEM.replace(it, "") }
            else -> lines.filterNot(HEADING::containsMatchIn).ifEmpty {
                lines.map { HEADING.replace(it, "") }
            }
        }

        for (candidate in candidates) {
            val spoken = clean(candidate)
            if (spoken != null) return spoken
        }
        return null
    }

    /**
     * Splits an already-sanitized reply into speakable clauses for TTS
     * streaming. Empty input yields an empty list.
     */
    fun clauses(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = SENTENCE_END.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(trimmed) }
    }

    private fun clean(line: String): String? {
        var text = line
        text = BRACKETED.replace(text, " ")
        text = PARENTHETICAL_DIRECTION.replace(text, " ")
        text = MARKDOWN_EMPHASIS.replace(text, "")
        text = META_PREAMBLE.replace(text, "")
        text = WHITESPACE.replace(text, " ").trim()
        text = text.trim('"', '“', '”', ' ')
        if (!text.any(Char::isLetterOrDigit)) return null

        text = firstSentences(text, MAX_SENTENCES)
        text = truncateOnWordBoundary(text, MAX_CHARACTERS)
        return text.ifBlank { null }
    }

    private fun firstSentences(text: String, limit: Int): String {
        val sentences = SENTENCE_END.split(text)
        if (sentences.size <= limit) return text
        return sentences.take(limit).joinToString(" ").trim()
    }

    /**
     * Cuts at a word boundary rather than mid-syllable: a speech engine handed
     * "we can offer a replaceme" says exactly that.
     */
    private fun truncateOnWordBoundary(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val window = text.take(limit)
        val lastSpace = window.lastIndexOf(' ')
        val cut = if (lastSpace > limit / 2) window.take(lastSpace) else window
        return cut.trimEnd().trimEnd(',', ';', ':', '-', '—') + "."
    }
}
