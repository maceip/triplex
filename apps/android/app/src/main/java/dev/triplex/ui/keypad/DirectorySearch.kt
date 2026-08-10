package dev.triplex.ui.keypad

import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.RecentCall
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Keeps a dial string to the characters Telecom accepts.
 *
 * Public because it is the one normalization both the keypad and the incoming
 * `tel:` intent must agree on; it used to be a private helper in
 * `DialerActivity`.
 */
fun normalizeDialString(value: String): String =
    value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

/**
 * A phone number reduced to what two numbers can honestly be compared on.
 *
 * Formatting is thrown away, and a North-American `1` country code is dropped so
 * `+1 318-555-0100` and `3185550100` are one entry rather than two.
 */
fun comparableNumber(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
}

/**
 * True when [query]'s digits identify [candidate].
 *
 * A substring match, because people search by the last four digits as often as
 * by the first; plus the country-code case from the other direction, where the
 * user types the `1` the stored number omits.
 */
fun numberMatches(candidate: String, query: String): Boolean {
    val target = comparableNumber(candidate)
    val digits = query.filter(Char::isDigit)
    if (digits.isEmpty() || target.isEmpty()) return false
    if (target.contains(digits)) return true
    return digits.length > 1 && digits.startsWith("1") && target.startsWith(digits.drop(1))
}

/** Where a search hit came from, which is what decides its subtitle and icon. */
enum class SearchSource { CONTACT, RECENT }

/** One row of unified search output. */
data class KeypadSearchResult(
    val id: String,
    val title: String,
    val number: String,
    val subtitle: String,
    val source: SearchSource,
    val starred: Boolean = false,
    val photoUri: String? = null,
)

/**
 * The unified search of reskin.md §3.1: one query over contacts *and* call
 * history, replacing the three per-section search fields.
 *
 * A contact matches by T9 ([SmartDial]), by plain substring on the name — so a
 * hardware keyboard or the system IME still works — or by number. History rows
 * match by cached name or number, and are dropped when a contact already covers
 * the same number, so calling someone in your contacts does not produce two rows.
 */
fun searchDirectory(
    query: String,
    contacts: List<DialerContact>,
    recents: List<RecentCall>,
): List<KeypadSearchResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val digits = trimmed.filter(Char::isDigit)
    val hasLetters = trimmed.any(Char::isLetter)

    fun nameMatches(name: String): Boolean = when {
        name.isBlank() -> false
        hasLetters -> name.contains(trimmed, ignoreCase = true)
        else -> SmartDial.matches(name, digits)
    }

    val results = mutableListOf<KeypadSearchResult>()
    val seen = mutableSetOf<String>()

    fun key(number: String, title: String): String =
        comparableNumber(number).ifEmpty { title.lowercase() }

    for (contact in contacts) {
        if (!nameMatches(contact.name) && !numberMatches(contact.number, digits)) continue
        if (!seen.add(key(contact.number, contact.name))) continue
        results += KeypadSearchResult(
            id = "contact:${contact.id}:${contact.number}",
            title = contact.name.ifBlank { contact.number },
            number = contact.number,
            subtitle = contact.number,
            source = SearchSource.CONTACT,
            starred = contact.starred,
            photoUri = contact.photoUri,
        )
    }

    for (recent in recents) {
        if (!nameMatches(recent.name) && !numberMatches(recent.number, digits)) continue
        val title = recent.name.ifBlank { recent.number.ifBlank { UNKNOWN_CALLER } }
        if (!seen.add(key(recent.number, title))) continue
        results += KeypadSearchResult(
            id = "recent:${recent.id}",
            title = title,
            number = recent.number,
            subtitle = "${recent.typeLabel} · ${recent.whenLabel}",
            source = SearchSource.RECENT,
            photoUri = recent.photoUri,
        )
    }
    return results
}

/** A day's worth of calls under one header. */
data class RecentDayGroup(
    val label: String,
    val date: LocalDate,
    val calls: List<RecentCall>,
)

/**
 * Groups recents into day sections, newest day first, preserving the order the
 * repository returned within each day.
 *
 * [today] is a parameter rather than a `LocalDate.now()` call so "Today" and
 * "Yesterday" are testable without freezing the clock.
 */
fun groupRecentsByDay(
    recents: List<RecentCall>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentDayGroup> {
    if (recents.isEmpty()) return emptyList()
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return recents
        .groupBy { call -> Instant.ofEpochMilli(call.timestampMs).atZone(zone).toLocalDate() }
        .map { (date, calls) ->
            val label = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(dateFormat)
            }
            RecentDayGroup(label = label, date = date, calls = calls)
        }
        .sortedByDescending(RecentDayGroup::date)
}

/** Shown when neither the call log nor contacts can name the other party. */
const val UNKNOWN_CALLER = "Unknown caller"
