package dev.triplex.dialer

import android.provider.CallLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a logged call ended, kept separate from its display label. */
enum class RecentCallType(val label: String) {
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    MISSED("Missed"),
    VOICEMAIL("Voicemail"),
    REJECTED("Declined"),
    BLOCKED("Blocked"),
    OTHER("Call");

    companion object {
        fun fromCallLogType(type: Int): RecentCallType = when (type) {
            CallLog.Calls.INCOMING_TYPE -> INCOMING
            CallLog.Calls.OUTGOING_TYPE -> OUTGOING
            CallLog.Calls.MISSED_TYPE -> MISSED
            CallLog.Calls.VOICEMAIL_TYPE -> VOICEMAIL
            CallLog.Calls.REJECTED_TYPE -> REJECTED
            CallLog.Calls.BLOCKED_TYPE -> BLOCKED
            else -> OTHER
        }
    }
}

data class RecentCall(
    val id: Long,
    val number: String,
    val name: String,
    val typeLabel: String,
    val whenLabel: String,
    val type: RecentCallType = RecentCallType.OTHER,
    val timestampMs: Long = 0L,
    /** Time of day only; the day itself is carried by the group header. */
    val timeLabel: String = whenLabel,
    val durationSeconds: Long = 0L,
    /** A missed call the user has not seen yet. */
    val unread: Boolean = false,
    val photoUri: String? = null,
    val lookupUri: String? = null,
)

data class DialerContact(
    val id: Long,
    val name: String,
    val number: String,
    val starred: Boolean,
    val photoUri: String? = null,
    val lookupUri: String? = null,
)

/** Digits only, `+` included so an international prefix survives. */
internal fun digitsOf(number: String): String = number.filter { it.isDigit() || it == '+' }

/**
 * The device directory as live state.
 *
 * A Hilt `@Singleton` (reskin.md §2.4) with exactly one instance in the process:
 * the keypad and `CallerIdentityResolver` read the same flows instead of each
 * constructing a repository and each paying for its own provider queries.
 *
 * Nothing here touches Android. Every provider call goes through
 * [DirectoryDataSource], which is what lets a JVM test drive paging, permission
 * transitions and read-marking with `runTest`'s scheduler and no Robolectric.
 *
 * **Reactivity.** [recents] and [contacts] are `flatMapLatest` over a permission
 * generation counter and (for recents) the current page size. Each inner flow is
 * the data source's provider-change stream, so a change notification reloads
 * exactly one list. The observers are torn down and rebuilt whenever the
 * generation or page size changes — that is the whole lifecycle, and it is owned
 * by the flow rather than by an activity callback.
 *
 * **Permission-denied.** The data source reports empty and registers nothing;
 * the flows sit at `emptyList()` without spinning. [refresh] bumps the
 * generation, which re-evaluates access and registers the observers for real —
 * so the surface starts working the moment the user grants the permission,
 * without a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerDirectoryRepository(
    private val source: DirectoryDataSource,
    private val scope: CoroutineScope,
) {
    /** Bumped by [refresh]; re-evaluates permissions and rebuilds the observers. */
    private val accessGeneration = MutableStateFlow(0)
    private val recentsLimit = MutableStateFlow(RECENTS_PAGE)

    val recents: StateFlow<List<RecentCall>> =
        combine(accessGeneration, recentsLimit) { _, limit -> limit }
            .flatMapLatest { limit ->
                if (!source.hasCallLogAccess()) {
                    flowOf(emptyList())
                } else {
                    source.callLogChanges().mapLatest { source.loadRecents(limit) }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val contacts: StateFlow<List<DialerContact>> = accessGeneration
        .flatMapLatest {
            if (!source.hasContactsAccess()) {
                flowOf(emptyList())
            } else {
                source.contactChanges().mapLatest { source.loadContacts() }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Starred contacts, in the order the provider returned them. */
    val favorites: StateFlow<List<DialerContact>> = contacts
        .map { list -> list.filter(DialerContact::starred) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while the call log has more rows than the current page shows. */
    val hasMoreRecents: StateFlow<Boolean> =
        combine(recents, recentsLimit) { list, limit -> list.size >= limit }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Re-evaluate directory permissions and rebuild the provider observers.
     *
     * Cheap and idempotent. Called on resume and after a permission grant; a
     * running app does not otherwise need it, because the observers push.
     */
    fun refresh() {
        accessGeneration.update { it + 1 }
    }

    /** Grow the recents page. Paging on scroll, per reskin.md §2.4. */
    fun loadMoreRecents() {
        if (!hasMoreRecents.value) return
        recentsLimit.update { it + RECENTS_PAGE }
    }

    /** Every logged call to or from [number], newest first. */
    suspend fun historyFor(number: String): List<RecentCall> =
        source.loadHistoryFor(number, HISTORY_PAGE)

    /**
     * Clear the missed-call flag on everything currently unread.
     *
     * The keypad calls this when it comes to the foreground: the user has now
     * seen the list, so the platform's "new missed call" state is stale.
     * Requires `WRITE_CALL_LOG`, which the manifest already declares; without it
     * the data source reports zero rows and nothing else happens.
     */
    fun markMissedCallsRead() {
        val unread = recents.value.filter(RecentCall::unread).map(RecentCall::id)
        if (unread.isEmpty()) return
        scope.launch { source.markCallsRead(unread) }
    }

    companion object {
        /** Kept at the pre-Phase-2 page size; scrolling adds another page. */
        const val RECENTS_PAGE = 100
        const val HISTORY_PAGE = 50
    }
}
