package dev.triplex.dialer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The provider seam, faked.
 *
 * Shared by the repository test and the keypad view-model test, which is the
 * clearest evidence that [DirectoryDataSource] is the right cut: everything
 * above it runs on the JVM.
 *
 * The change flows are `StateFlow`s so they reproduce the real contract — one
 * emission on collection, one per notification — with a counter instead of a
 * `ContentObserver`.
 */
internal class FakeDirectoryDataSource : DirectoryDataSource {

    var callLogAccess: Boolean = true
    var contactsAccess: Boolean = true
    var callLogWriteAccess: Boolean = true

    var recents: List<RecentCall> = emptyList()
    var contacts: List<DialerContact> = emptyList()
    var history: List<RecentCall> = emptyList()

    /** The page size the repository last asked for; paging asserts on this. */
    var lastRecentsLimit: Int = 0
        private set
    var recentsLoads: Int = 0
        private set
    val markedRead = mutableListOf<Long>()
    var lastHistoryNumber: String? = null
        private set

    private val callLogTicks = MutableStateFlow(0)
    private val contactTicks = MutableStateFlow(0)

    fun notifyCallLog() = callLogTicks.update { it + 1 }

    fun notifyContacts() = contactTicks.update { it + 1 }

    override fun hasCallLogAccess(): Boolean = callLogAccess

    override fun hasContactsAccess(): Boolean = contactsAccess

    override fun hasCallLogWriteAccess(): Boolean = callLogWriteAccess

    override fun callLogChanges(): Flow<Unit> = callLogTicks.map { }

    override fun contactChanges(): Flow<Unit> = contactTicks.map { }

    override suspend fun loadRecents(limit: Int): List<RecentCall> {
        lastRecentsLimit = limit
        recentsLoads++
        return recents.take(limit)
    }

    override suspend fun loadContacts(): List<DialerContact> = contacts

    override suspend fun loadHistoryFor(number: String, limit: Int): List<RecentCall> {
        lastHistoryNumber = number
        return history.take(limit)
    }

    override suspend fun markCallsRead(ids: List<Long>): Int {
        if (!callLogWriteAccess) return 0
        markedRead += ids
        return ids.size
    }
}

/** Terse builders so the tests read as scenarios rather than as constructor calls. */
internal fun testRecent(
    id: Long,
    number: String = "+1318555$id",
    name: String = "",
    type: RecentCallType = RecentCallType.INCOMING,
    unread: Boolean = false,
    timestampMs: Long = 1_770_000_000_000L,
) = RecentCall(
    id = id,
    number = number,
    name = name,
    typeLabel = type.label,
    whenLabel = "14/03/2026 09:00",
    type = type,
    timestampMs = timestampMs,
    timeLabel = "09:00",
    unread = unread,
)

internal fun testContact(
    id: Long,
    name: String,
    number: String,
    starred: Boolean = false,
) = DialerContact(id = id, name = name, number = number, starred = starred)
