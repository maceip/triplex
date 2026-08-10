package dev.triplex.dialer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reactive directory, driven on the JVM.
 *
 * The repository takes its data source and its scope as constructor parameters,
 * so `runTest`'s scheduler is the only clock and there is no Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerDirectoryRepositoryTest {

    private val source = FakeDirectoryDataSource()

    @Test
    fun `a call log notification reloads recents without anyone calling refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = create(backgroundScope)
            advanceUntilIdle()
            assertTrue(repository.recents.value.isEmpty())

            source.recents = listOf(testRecent(1), testRecent(2))
            source.notifyCallLog()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), repository.recents.value.map(RecentCall::id))
        }

    @Test
    fun `a contacts notification reloads contacts and favorites`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = create(backgroundScope)
            source.contacts = listOf(
                testContact(1, "Jane Kowalski", "+13185550100", starred = true),
                testContact(2, "Bruno Vega", "+15045557788"),
            )
            source.notifyContacts()
            advanceUntilIdle()

            assertEquals(2, repository.contacts.value.size)
            assertEquals(listOf("Jane Kowalski"), repository.favorites.value.map(DialerContact::name))
        }

    @Test
    fun `denied permissions leave the lists empty and register nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            source.callLogAccess = false
            source.contactsAccess = false
            source.recents = listOf(testRecent(1))
            source.contacts = listOf(testContact(1, "Jane Kowalski", "+13185550100"))

            val repository = create(backgroundScope)
            advanceUntilIdle()

            assertTrue(repository.recents.value.isEmpty())
            assertTrue(repository.contacts.value.isEmpty())
            assertEquals(0, source.recentsLoads)
        }

    @Test
    fun `refresh revives the directory after a permission is granted`() =
        runTest(UnconfinedTestDispatcher()) {
            source.callLogAccess = false
            source.recents = listOf(testRecent(1))
            val repository = create(backgroundScope)
            advanceUntilIdle()
            assertTrue(repository.recents.value.isEmpty())

            // Exactly what DialerActivity does after the permission dialog.
            source.callLogAccess = true
            repository.refresh()
            advanceUntilIdle()

            assertEquals(1, repository.recents.value.size)
        }

    @Test
    fun `loadMoreRecents asks for another page only while more rows exist`() =
        runTest(UnconfinedTestDispatcher()) {
            val page = DialerDirectoryRepository.RECENTS_PAGE
            source.recents = (1L..(page + 5L)).map { testRecent(it) }
            val repository = create(backgroundScope)
            advanceUntilIdle()

            assertEquals(page, source.lastRecentsLimit)
            assertTrue(repository.hasMoreRecents.value)

            repository.loadMoreRecents()
            advanceUntilIdle()

            assertEquals(page * 2, source.lastRecentsLimit)
            assertEquals(page + 5, repository.recents.value.size)
            // The tail is now shorter than the page: nothing left to fetch.
            assertFalse(repository.hasMoreRecents.value)

            val loadsBefore = source.recentsLoads
            repository.loadMoreRecents()
            advanceUntilIdle()
            assertEquals(loadsBefore, source.recentsLoads)
        }

    @Test
    fun `markMissedCallsRead clears only the unread rows`() =
        runTest(UnconfinedTestDispatcher()) {
            source.recents = listOf(
                testRecent(1, type = RecentCallType.MISSED, unread = true),
                testRecent(2, type = RecentCallType.INCOMING),
                testRecent(3, type = RecentCallType.MISSED, unread = true),
            )
            val repository = create(backgroundScope)
            advanceUntilIdle()

            repository.markMissedCallsRead()
            advanceUntilIdle()

            assertEquals(listOf(1L, 3L), source.markedRead)
        }

    @Test
    fun `markMissedCallsRead is a no-op when nothing is unread`() =
        runTest(UnconfinedTestDispatcher()) {
            source.recents = listOf(testRecent(1, type = RecentCallType.INCOMING))
            val repository = create(backgroundScope)
            advanceUntilIdle()

            repository.markMissedCallsRead()
            advanceUntilIdle()

            assertTrue(source.markedRead.isEmpty())
        }

    @Test
    fun `historyFor asks the source for that number`() = runTest(UnconfinedTestDispatcher()) {
        source.history = listOf(testRecent(7), testRecent(8))
        val repository = create(backgroundScope)

        val history = repository.historyFor("+13185550100")

        assertEquals("+13185550100", source.lastHistoryNumber)
        assertEquals(2, history.size)
    }

    private fun create(scope: CoroutineScope) = DialerDirectoryRepository(source, scope)
}
