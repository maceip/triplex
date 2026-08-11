package dev.triplex.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.data.local.SecureStorage
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class DebugSipProvisioningReceiver : BroadcastReceiver() {
    @Inject lateinit var secureStorage: SecureStorage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROVISION_SIP) return
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
            .ifBlank { "phone.plivo.com" }
        if (username.isBlank() || password.length < 5 || domain.isBlank()) {
            Timber.e("Rejected incomplete debug SIP provisioning")
            return
        }
        secureStorage.setPlivoUsername(username)
        secureStorage.setPlivoPassword(password)
        secureStorage.setPlivoDomain(domain)
        Timber.i("Provisioned debug SIP endpoint %s@%s", username, domain)
    }

    private companion object {
        const val ACTION_PROVISION_SIP = "dev.triplex.debug.PROVISION_SIP"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_DOMAIN = "domain"
    }
}
