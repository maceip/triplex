package dev.triplex.dialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every Android touch point the directory needs, behind one interface.
 *
 * This is the seam that keeps [DialerDirectoryRepository] — and through it
 * `KeypadViewModel` — constructible on the JVM. `ContentResolver`, `Cursor`,
 * `ContentObserver` and the permission checks live on the far side of it; a unit
 * test hands the repository a list and a change signal instead.
 *
 * The change flows are the reactive half of reskin.md §2.4: they emit once on
 * collection and again for every provider notification, so the call log updates
 * the moment a call ends without anyone calling `refresh()`.
 */
interface DirectoryDataSource {

    /** `READ_CALL_LOG`. Re-checked on every access; the user can revoke at any time. */
    fun hasCallLogAccess(): Boolean

    /** `READ_CONTACTS`. */
    fun hasContactsAccess(): Boolean

    /** `WRITE_CALL_LOG`, needed to clear the missed-call flag. */
    fun hasCallLogWriteAccess(): Boolean

    /** Emits `Unit` immediately, then once per call-log change notification. */
    fun callLogChanges(): Flow<Unit>

    /** Emits `Unit` immediately, then once per contacts change notification. */
    fun contactChanges(): Flow<Unit>

    suspend fun loadRecents(limit: Int): List<RecentCall>

    suspend fun loadContacts(): List<DialerContact>

    /** Every logged call to or from [number], newest first. Backs the expanded recents row. */
    suspend fun loadHistoryFor(number: String, limit: Int): List<RecentCall>

    /** Clears `NEW`/`IS_READ` on the given call-log rows. Returns how many rows changed. */
    suspend fun markCallsRead(ids: List<Long>): Int
}

/**
 * The real thing: `ContentResolver` queries and provider observers.
 *
 * Permission-denied is a first-class path, not an exception to recover from.
 * Every entry point re-checks the permission it needs and returns empty, and
 * observer registration is skipped entirely when the permission is absent — a
 * `ContentObserver` on a provider the app cannot read would either throw or spin
 * on notifications it cannot act on. [DialerDirectoryRepository.refresh] is what
 * re-evaluates this after the user grants access.
 */
@Singleton
class ContentResolverDirectoryDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : DirectoryDataSource {

    private val resolver get() = context.contentResolver

    override fun hasCallLogAccess(): Boolean = hasPermission(Manifest.permission.READ_CALL_LOG)

    override fun hasContactsAccess(): Boolean = hasPermission(Manifest.permission.READ_CONTACTS)

    override fun hasCallLogWriteAccess(): Boolean = hasPermission(Manifest.permission.WRITE_CALL_LOG)

    override fun callLogChanges(): Flow<Unit> =
        providerChanges(CallLog.Calls.CONTENT_URI, ::hasCallLogAccess)

    override fun contactChanges(): Flow<Unit> =
        providerChanges(ContactsContract.Contacts.CONTENT_URI, ::hasContactsAccess)

    /**
     * One `ContentObserver`, scoped to the collector.
     *
     * `awaitClose` unregisters when the collecting coroutine is cancelled, which
     * is what the repository does whenever permissions are re-evaluated or the
     * recents page size grows — so observers never outlive the flow that owns
     * them, and there is never more than one per active collection.
     */
    private fun providerChanges(uri: Uri, hasAccess: () -> Boolean): Flow<Unit> = callbackFlow {
        trySend(Unit)
        if (!hasAccess()) {
            // Nothing to observe yet. Stay open and idle rather than failing:
            // the repository re-collects this flow once access is granted.
            awaitClose { }
            return@callbackFlow
        }
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            resolver.registerContentObserver(uri, true, observer)
            true
        } catch (error: SecurityException) {
            // Racing a revoke, or an OEM provider that guards registration.
            Timber.w(error, "Cannot observe %s; directory stays static until refresh()", uri)
            false
        }
        awaitClose {
            if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    override suspend fun loadRecents(limit: Int): List<RecentCall> = withContext(Dispatchers.IO) {
        if (!hasCallLogAccess()) return@withContext emptyList()
        queryCalls(CallLog.Calls.CONTENT_URI, limit)
    }

    override suspend fun loadHistoryFor(number: String, limit: Int): List<RecentCall> =
        withContext(Dispatchers.IO) {
            if (!hasCallLogAccess() || number.isBlank()) return@withContext emptyList()
            val uri = Uri.withAppendedPath(CallLog.Calls.CONTENT_FILTER_URI, Uri.encode(number))
            queryCalls(uri, limit)
        }

    private fun queryCalls(uri: Uri, limit: Int): List<RecentCall> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NEW,
            CallLog.Calls.IS_READ,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.CACHED_LOOKUP_URI,
        )
        val dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val result = mutableListOf<RecentCall>()
        try {
            resolver.query(
                uri,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val newColumn = cursor.getColumnIndex(CallLog.Calls.NEW)
                val readColumn = cursor.getColumnIndex(CallLog.Calls.IS_READ)
                val photoColumn = cursor.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI)
                val lookupColumn = cursor.getColumnIndex(CallLog.Calls.CACHED_LOOKUP_URI)
                while (cursor.moveToNext()) {
                    val timestamp = cursor.getLong(dateColumn)
                    val type = RecentCallType.fromCallLogType(cursor.getInt(typeColumn))
                    val unread = (newColumn >= 0 && cursor.getInt(newColumn) != 0) ||
                        (readColumn >= 0 && cursor.getInt(readColumn) == 0)
                    result += RecentCall(
                        id = cursor.getLong(idColumn),
                        number = cursor.getString(numberColumn).orEmpty(),
                        name = cursor.getString(nameColumn).orEmpty(),
                        typeLabel = type.label,
                        whenLabel = dateTimeFormat.format(Date(timestamp)),
                        type = type,
                        timestampMs = timestamp,
                        timeLabel = timeFormat.format(Date(timestamp)),
                        durationSeconds = cursor.getLong(durationColumn),
                        unread = unread && type == RecentCallType.MISSED,
                        photoUri = photoColumn.takeIf { it >= 0 }?.let(cursor::getString),
                        lookupUri = lookupColumn.takeIf { it >= 0 }?.let(cursor::getString),
                    )
                }
            }
        } catch (error: SecurityException) {
            Timber.w(error, "Call log read denied")
        }
        return result
    }

    override suspend fun loadContacts(): List<DialerContact> = withContext(Dispatchers.IO) {
        if (!hasContactsAccess()) return@withContext emptyList()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        )
        val result = linkedMapOf<String, DialerContact>()
        try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE",
            )?.use { cursor ->
                val idColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
                )
                val numberColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val starredColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val photoColumn =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val lookupColumn =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idColumn)
                    val lookupKey = lookupColumn.takeIf { it >= 0 }?.let(cursor::getString)
                    val contact = DialerContact(
                        id = contactId,
                        name = cursor.getString(nameColumn).orEmpty(),
                        number = cursor.getString(numberColumn).orEmpty(),
                        starred = cursor.getInt(starredColumn) == 1,
                        photoUri = photoColumn.takeIf { it >= 0 }?.let(cursor::getString),
                        lookupUri = lookupKey?.let {
                            ContactsContract.Contacts.getLookupUri(contactId, it)?.toString()
                        },
                    )
                    result.putIfAbsent("${contact.id}:${digitsOf(contact.number)}", contact)
                }
            }
        } catch (error: SecurityException) {
            Timber.w(error, "Contacts read denied")
        }
        result.values.toList()
    }

    override suspend fun markCallsRead(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty() || !hasCallLogWriteAccess()) return@withContext 0
        val values = ContentValues().apply {
            put(CallLog.Calls.NEW, 0)
            put(CallLog.Calls.IS_READ, 1)
        }
        val selection = "${CallLog.Calls._ID} IN (${ids.joinToString(",")})"
        try {
            resolver.update(CallLog.Calls.CONTENT_URI, values, selection, null)
        } catch (error: SecurityException) {
            Timber.w(error, "Call log write denied")
            0
        } catch (error: IllegalArgumentException) {
            Timber.w(error, "Call log provider rejected the read-marking update")
            0
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
