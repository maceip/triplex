package dev.triplex.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecentCall(
    val id: Long,
    val number: String,
    val name: String,
    val typeLabel: String,
    val whenLabel: String
)

data class DialerContact(
    val id: Long,
    val name: String,
    val number: String,
    val starred: Boolean
)

class DialerDirectoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableRecents = MutableStateFlow<List<RecentCall>>(emptyList())
    private val mutableContacts = MutableStateFlow<List<DialerContact>>(emptyList())

    val recents: StateFlow<List<RecentCall>> = mutableRecents.asStateFlow()
    val contacts: StateFlow<List<DialerContact>> = mutableContacts.asStateFlow()

    fun refresh() {
        scope.launch {
            if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
                mutableRecents.value = loadRecents()
            }
            if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                mutableContacts.value = loadContacts()
            }
        }
    }

    private fun loadRecents(): List<RecentCall> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val result = mutableListOf<RecentCall>()
        appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 100"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            while (cursor.moveToNext()) {
                result += RecentCall(
                    id = cursor.getLong(idColumn),
                    number = cursor.getString(numberColumn).orEmpty(),
                    name = cursor.getString(nameColumn).orEmpty(),
                    typeLabel = callTypeLabel(cursor.getInt(typeColumn)),
                    whenLabel = formatter.format(Date(cursor.getLong(dateColumn)))
                )
            }
        }
        return result
    }

    private fun loadContacts(): List<DialerContact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        val result = linkedMapOf<String, DialerContact>()
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val starredColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (cursor.moveToNext()) {
                val contact = DialerContact(
                    id = cursor.getLong(idColumn),
                    name = cursor.getString(nameColumn).orEmpty(),
                    number = cursor.getString(numberColumn).orEmpty(),
                    starred = cursor.getInt(starredColumn) == 1
                )
                result.putIfAbsent("${contact.id}:${normalizeNumber(contact.number)}", contact)
            }
        }
        return result.values.toList()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun callTypeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        CallLog.Calls.REJECTED_TYPE -> "Declined"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Call"
    }

    private fun normalizeNumber(number: String): String {
        return number.filter { it.isDigit() || it == '+' }
    }
}
