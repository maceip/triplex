package dev.triplex.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "triplex_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _deviceToken = MutableStateFlow<String?>(null)
    val deviceToken: Flow<String?> = _deviceToken.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: Flow<String?> = _deviceId.asStateFlow()

    fun getDeviceToken(): String? {
        val token = encryptedPrefs.getString("device_token", null)
        _deviceToken.value = token
        return token
    }

    fun setDeviceToken(token: String) {
        encryptedPrefs.edit().putString("device_token", token).apply()
        _deviceToken.value = token
        Timber.d("Device token stored securely")
    }

    fun getDeviceId(): String? {
        return encryptedPrefs.getString("device_id", null)
    }

    fun setDeviceId(id: String) {
        encryptedPrefs.edit().putString("device_id", id).apply()
        _deviceId.value = id
    }

    fun getPlivoUsername(): String? {
        return encryptedPrefs.getString("plivo_sip_username", null)
    }

    fun setPlivoUsername(username: String) {
        encryptedPrefs.edit().putString("plivo_sip_username", username).apply()
    }

    fun getPlivoPassword(): String? {
        return encryptedPrefs.getString("plivo_sip_password", null)
    }

    fun setPlivoPassword(password: String) {
        encryptedPrefs.edit().putString("plivo_sip_password", password).apply()
    }

    fun getPlivoDomain(): String {
        return encryptedPrefs.getString("plivo_sip_domain", null)
            ?.takeIf { it.isNotBlank() }
            ?: "phone.plivo.com"
    }

    fun setPlivoDomain(domain: String) {
        encryptedPrefs.edit().putString("plivo_sip_domain", domain.trim()).apply()
    }

    fun getVoiceProfileRef(): String? {
        return encryptedPrefs.getString("voice_profile_ref", null)
    }

    fun setVoiceProfileRef(ref: String) {
        encryptedPrefs.edit().putString("voice_profile_ref", ref).apply()
    }

    /** User attested that SIM conditional forward points at the Triplex line. */
    fun isRoutingAttested(): Boolean =
        encryptedPrefs.getBoolean("routing_attested", false)

    fun setRoutingAttested(attested: Boolean) {
        encryptedPrefs.edit().putBoolean("routing_attested", attested).apply()
    }

    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        _deviceToken.value = null
        _deviceId.value = null
    }
}
