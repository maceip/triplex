package dev.triplex.ui.keypad

import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.RecentCall
import dev.triplex.dialer.RecentCallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The unified search and the recents grouping, off-device.
 *
 * Both are plain functions over data classes, which is the point: the keypad's
 * hardest logic is exercised without Compose, Robolectric or a content provider.
 */
class DirectorySearchTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 3, 14)

    @Test
    fun `dial strings keep only what Telecom accepts`() {
        assertEquals("+13185550100", normalizeDialString("+1 (318) 555-0100"))
        assertEquals("*67#", normalizeDialString("*67#"))
        assertEquals("", normalizeDialString("Jane"))
    }

    @Test
    fun `comparable numbers drop formatting and a north american country code`() {
        assertEquals("3185550100", comparableNumber("+1 (318) 555-0100"))
        assertEquals("3185550100", comparableNumber("318-555-0100"))
        assertEquals("3185550100", comparableNumber("318 555 0100"))
        assertEquals("3185550100", comparableNumber("13185550100"))
        // Not eleven digits starting with 1: left alone.
        assertEquals("443185550100", comparableNumber("+44 318 555 0100"))
    }

    @Test
    fun `number matching survives formatting on either side`() {
        assertTrue(numberMatches("+1 (318) 555-0100", "3185550100"))
        assertTrue(numberMatches("3185550100", "13185550100"))
        assertTrue(numberMatches("+1 318-555-0100", "5550100"))
        // The last four digits are how people actually search.
        assertTrue(numberMatches("+1 318-555-0100", "0100"))
        assertFalse(numberMatches("+1 318-555-0100", "9999"))
        assertFalse(numberMatches("+1 318-555-0100", ""))
    }

    @Test
    fun `one query searches contacts and history together`() {
        val results = searchDirectory(
            query = "555",
            contacts = listOf(contact(1, "Jane Kowalski", "+1 318-555-0100")),
            recents = listOf(recent(10, "+1 504-555-7788", "Bruno Vega")),
        )
        assertEquals(2, results.size)
        assertEquals("Jane Kowalski", results[0].title)
        assertEquals(SearchSource.CONTACT, results[0].source)
        assertEquals("Bruno Vega", results[1].title)
        assertEquals(SearchSource.RECENT, results[1].source)
    }

    @Test
    fun `a contact hides the history rows for the same number`() {
        val results = searchDirectory(
            query = "Jane",
            contacts = listOf(contact(1, "Jane Kowalski", "+1 318-555-0100")),
            recents = listOf(
                recent(10, "3185550100", "Jane Kowalski"),
                recent(11, "13185550100", "Jane Kowalski"),
            ),
        )
        assertEquals(1, results.size)
        assertEquals(SearchSource.CONTACT, results.single().source)
    }

    @Test
    fun `a digit query goes through T9`() {
        val results = searchDirectory(
            query = "526",
            contacts = listOf(
                contact(1, "Jane Kowalski", "+1 318-555-0100"),
                contact(2, "Bruno Vega", "+1 504-555-7788"),
            ),
            recents = emptyList(),
        )
        assertEquals(listOf("Jane Kowalski"), results.map { it.title })
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        val results = searchDirectory(
            query = "   ",
            contacts = listOf(contact(1, "Jane Kowalski", "+1 318-555-0100")),
            recents = listOf(recent(10, "+1 504-555-7788", "Bruno Vega")),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `an unnamed history row is still reachable by number`() {
        val results = searchDirectory(
            query = "7788",
            contacts = emptyList(),
            recents = listOf(recent(10, "+1 504-555-7788", "")),
        )
        assertEquals(1, results.size)
        assertEquals("+1 504-555-7788", results.single().title)
    }

    @Test
    fun `recents group into days newest first`() {
        val groups = groupRecentsByDay(
            recents = listOf(
                recent(1, "+13185550100", "Jane", at(2026, 3, 14, 9)),
                recent(2, "+13185550100", "Jane", at(2026, 3, 14, 8)),
                recent(3, "+15045557788", "Bruno", at(2026, 3, 13, 20)),
                recent(4, "+15045557788", "Bruno", at(2026, 3, 2, 11)),
            ),
            today = today,
            zone = zone,
        )
        assertEquals(3, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(2, groups[0].calls.size)
        assertEquals("Yesterday", groups[1].label)
        assertEquals(LocalDate.of(2026, 3, 2), groups[2].date)
        // Not "Today" or "Yesterday": an older day gets a real date label.
        assertTrue(groups[2].label != "Today" && groups[2].label != "Yesterday")
    }

    @Test
    fun `grouping an empty list produces no headers`() {
        assertTrue(groupRecentsByDay(emptyList(), today, zone).isEmpty())
    }

    private fun contact(id: Long, name: String, number: String, starred: Boolean = false) =
        DialerContact(id = id, name = name, number = number, starred = starred)

    private fun recent(
        id: Long,
        number: String,
        name: String,
        timestampMs: Long = at(2026, 3, 14, 9),
    ) = RecentCall(
        id = id,
        number = number,
        name = name,
        typeLabel = RecentCallType.INCOMING.label,
        whenLabel = "14/03/2026 09:00",
        type = RecentCallType.INCOMING,
        timestampMs = timestampMs,
        timeLabel = "09:00",
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
}
