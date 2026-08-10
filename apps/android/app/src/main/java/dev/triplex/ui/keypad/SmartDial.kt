package dev.triplex.ui.keypad

/**
 * T9 name matching for the keypad (reskin.md §2.4, §3.1).
 *
 * Pure Kotlin with no Android and no state, so the whole smart-dial behaviour is
 * exercised by a plain JUnit test.
 *
 * The rule is deliberately small: a name matches a digit string when the digits
 * spell a prefix of the name starting at *some word*, or spell the initials of a
 * run of words. That covers the three things people actually type — the start of
 * a first name, the start of a last name, and initials — without a relevance
 * score nobody can predict or test.
 */
object SmartDial {

    /** ITU-T E.161, `a`..`z` in order. */
    private const val LETTER_DIGITS = "22233344455566677778889999"

    private val WORD = Regex("[A-Za-z]+")

    /** The keypad digit for [char], or `null` when it is not a Latin letter. */
    fun digitFor(char: Char): Char? {
        val lower = char.lowercaseChar()
        return if (lower in 'a'..'z') LETTER_DIGITS[lower - 'a'] else null
    }

    /** [text] as the digits it would be typed with; non-letters are dropped. */
    fun toDigits(text: String): String = buildString {
        for (char in text) digitFor(char)?.let(::append)
    }

    /**
     * True when [query]'s digits are a T9 prefix of [name].
     *
     * Matching starts at any word boundary, so `5265` finds "Jane Kowalski"
     * through "Jane" + "K", and `526` finds it through "Jan". Initials are
     * matched separately, so `55` also finds it.
     *
     * A blank query never matches: an empty keypad is not a search.
     */
    fun matches(name: String, query: String): Boolean {
        val digits = query.filter(Char::isDigit)
        if (digits.isEmpty()) return false
        val words = WORD.findAll(name).map { toDigits(it.value) }.filter(String::isNotEmpty).toList()
        if (words.isEmpty()) return false

        for (start in words.indices) {
            val fromHere = words.subList(start, words.size)
            // "Type straight through": the words run together, which is how a
            // first-name prefix and a first-name+surname prefix are the same rule.
            if (fromHere.joinToString(separator = "").startsWith(digits)) return true
            val initials = fromHere.joinToString(separator = "") { it.take(1) }
            if (initials.startsWith(digits)) return true
        }
        return false
    }
}
