package dev.triplex.data.telecom

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A consent the app has to ask an `Activity` to obtain.
 *
 * The first four are the setup banners. [CALL_PERMISSION] is not a banner: it is
 * raised on demand when a call is attempted without `CALL_PHONE`.
 */
enum class SetupRequest {
    DEFAULT_DIALER,
    CALL_SCREENING,
    NOTIFICATIONS,
    DIRECTORY_ACCESS,
    CALL_PERMISSION,
}

/** Everything the keypad needs to know about the app's platform standing. */
data class DialerSetupStatus(
    val isDefaultDialer: Boolean = false,
    val isCallScreeningApp: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val directoryAccessEnabled: Boolean = false,
) {
    /** The outstanding items, in the order the keypad shows them. */
    val outstanding: List<SetupRequest>
        get() = buildList {
            if (!isDefaultDialer) add(SetupRequest.DEFAULT_DIALER)
            if (!isCallScreeningApp) add(SetupRequest.CALL_SCREENING)
            if (!notificationsEnabled) add(SetupRequest.NOTIFICATIONS)
            if (!directoryAccessEnabled) add(SetupRequest.DIRECTORY_ACCESS)
        }
}

/**
 * Reports the app's roles and permissions; it never asks for them.
 *
 * Requesting a role or a runtime permission needs an `Activity` result
 * launcher, so that half stays in `DialerActivity`. Splitting it this way is
 * what lets the keypad view model observe setup state on the JVM.
 */
interface DialerSetupRepository {
    val status: StateFlow<DialerSetupStatus>

    fun refresh()
}

@Singleton
class AndroidDialerSetupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : DialerSetupRepository {

    private val _status = MutableStateFlow(DialerSetupStatus())
    override val status: StateFlow<DialerSetupStatus> = _status.asStateFlow()

    override fun refresh() {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val roleManager = context.getSystemService(RoleManager::class.java)
        _status.value = DialerSetupStatus(
            isDefaultDialer = telecom.defaultDialerPackage == context.packageName,
            isCallScreeningApp = roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            directoryAccessEnabled = hasPermission(Manifest.permission.READ_CALL_LOG) &&
                hasPermission(Manifest.permission.READ_CONTACTS),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
