package dev.triplex.data.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A callable SIM/phone account, identified by a string the UI can round-trip. */
data class DialerPhoneAccount(
    val id: String,
    val label: String,
    val isDefault: Boolean,
)

/**
 * What happened when the app asked Telecom to dial.
 *
 * The two `Needs*` cases are not errors: they are the platform telling us a
 * consent step is missing. Only an `Activity` can launch those, so the
 * repository reports them and the activity's launchers handle them.
 */
sealed interface PlaceCallOutcome {
    data object Placed : PlaceCallOutcome
    data object NeedsDefaultDialerRole : PlaceCallOutcome
    data object NeedsCallPermission : PlaceCallOutcome
    data class Failed(val message: String) : PlaceCallOutcome
}

/**
 * Outgoing-call plumbing and SIM selection, behind an interface.
 *
 * `TelecomManager` is final and unmockable off-device, so the keypad view model
 * depends on this instead and a JVM test supplies a fake.
 */
interface TelecomAccountsRepository {
    val accounts: StateFlow<List<DialerPhoneAccount>>

    /** The account the next call will use, or `null` when nothing has been chosen. */
    val selectedAccountId: StateFlow<String?>

    /**
     * True when the user must pick a SIM before a call can be placed: more than
     * one callable account exists and the platform has no default outgoing
     * account to fall back on.
     */
    val requiresAccountChoice: StateFlow<Boolean>

    fun refresh()

    fun select(accountId: String)

    fun voicemailNumber(accountId: String?): String?

    fun placeCall(number: String, accountId: String?): PlaceCallOutcome
}

@Singleton
class AndroidTelecomAccountsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : TelecomAccountsRepository {

    private val handles = mutableMapOf<String, PhoneAccountHandle>()
    private val _accounts = MutableStateFlow<List<DialerPhoneAccount>>(emptyList())
    private val _selectedAccountId = MutableStateFlow<String?>(null)
    private val _requiresAccountChoice = MutableStateFlow(false)

    override val accounts: StateFlow<List<DialerPhoneAccount>> = _accounts.asStateFlow()
    override val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()
    override val requiresAccountChoice: StateFlow<Boolean> = _requiresAccountChoice.asStateFlow()

    private val telecom: TelecomManager
        get() = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    /**
     * Re-read the callable accounts.
     *
     * The multi-SIM fix lives here. The previous code fell back to
     * `singleOrNull()`, which is `null` on a dual-SIM device, so the selection
     * silently stayed empty and `EXTRA_PHONE_ACCOUNT_HANDLE` was never attached
     * — every call went out on whatever SIM the platform felt like. Now the
     * ambiguous case is stated instead of swallowed: [requiresAccountChoice]
     * goes true and the caller has to resolve it.
     */
    override fun refresh() {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return

        val defaultHandle = try {
            telecom.getDefaultOutgoingPhoneAccount("tel")
        } catch (error: SecurityException) {
            Timber.w(error, "Cannot read the default outgoing phone account")
            null
        }
        val callable = try {
            telecom.callCapablePhoneAccounts.orEmpty()
        } catch (error: SecurityException) {
            Timber.w(error, "Cannot list call-capable phone accounts")
            emptyList()
        }

        handles.clear()
        _accounts.value = callable.map { handle ->
            val id = idOf(handle)
            handles[id] = handle
            DialerPhoneAccount(
                id = id,
                label = telecom.getPhoneAccount(handle)?.label?.toString().orEmpty()
                    .ifBlank { "Phone account" },
                isDefault = handle == defaultHandle,
            )
        }

        val defaultId = defaultHandle?.let(::idOf)?.takeIf(handles::containsKey)
        // Keep a deliberate choice across refreshes; only drop it when the
        // account it named is gone (SIM removed, account disabled).
        if (_selectedAccountId.value !in handles.keys) {
            _selectedAccountId.value = defaultId ?: _accounts.value.singleOrNull()?.id
        }
        _requiresAccountChoice.value =
            _accounts.value.size > 1 && defaultId == null && _selectedAccountId.value == null
    }

    override fun select(accountId: String) {
        if (accountId !in handles.keys) return
        _selectedAccountId.value = accountId
        _requiresAccountChoice.value = false
    }

    override fun voicemailNumber(accountId: String?): String? = try {
        telecom.getVoiceMailNumber(accountId?.let(handles::get))
    } catch (error: SecurityException) {
        Timber.w(error, "Voicemail number read denied")
        null
    }

    override fun placeCall(number: String, accountId: String?): PlaceCallOutcome {
        if (number.isBlank()) return PlaceCallOutcome.Failed("There is no number to call")
        if (telecom.defaultDialerPackage != context.packageName) {
            return PlaceCallOutcome.NeedsDefaultDialerRole
        }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return PlaceCallOutcome.NeedsCallPermission
        }
        val extras = Bundle().apply {
            accountId?.let(handles::get)?.let {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
        }
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            PlaceCallOutcome.Placed
        } catch (error: SecurityException) {
            PlaceCallOutcome.Failed("Android blocked this call: ${error.message}")
        } catch (error: IllegalStateException) {
            PlaceCallOutcome.Failed("Telecom refused this call: ${error.message}")
        }
    }

    private fun idOf(handle: PhoneAccountHandle): String =
        "${handle.componentName.flattenToShortString()}:${handle.id}"

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
