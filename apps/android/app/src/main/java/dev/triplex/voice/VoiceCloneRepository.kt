package dev.triplex.voice

import dev.triplex.data.local.SecureStorage
import dev.triplex.data.remote.GatewayApi
import dev.triplex.data.remote.VoicePreparationException
import dev.triplex.data.remote.VoiceProfileStatus
import dev.triplex.data.repository.Result
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice-clone lifecycle for the Android app: consented capture, preparation,
 * cloned preview, and revocation.
 *
 * Placement note (UNIFICATION_PLAN.md): on-device synthesis is the preferred
 * end state and the Rust `TtsModel` seam is ready for it, but no maintained
 * on-device zero-shot cloning runtime exists for Android today. Preparation
 * and synthesis therefore run REMOTE_TTS through the gateway, and the app
 * shows that placement rather than hiding it.
 */
@Singleton
class VoiceCloneRepository @Inject constructor(
    private val api: GatewayApi,
    private val storage: SecureStorage
) {
    suspend fun status(): VoiceProfileStatus? {
        val token = storage.getDeviceToken() ?: return null
        return runCatching { api.getVoiceProfile(token) }
            .onFailure { Timber.w(it, "Voice profile status failed") }
            .getOrNull()
    }

    suspend fun uploadConsentedReference(
        reference: File,
        consentStatement: String,
        seconds: Int
    ): Result<VoiceProfileStatus> {
        val token = storage.getDeviceToken()
            ?: return Result.Error("Device is not enrolled")
        return try {
            val status = api.uploadVoiceProfile(
                consentStatement = consentStatement,
                referenceSeconds = seconds,
                wavBytes = reference.readBytes(),
                deviceToken = token
            )
            storage.setVoiceProfileRef(status.profile_id.orEmpty())
            Timber.i("Voice profile prepared: %s", status.profile_id)
            Result.Success(status)
        } catch (e: VoicePreparationException) {
            // Already phrased for the user; do not decorate it.
            Timber.w("Voice profile rejected: %s", e.message)
            Result.Error(e.message ?: "That recording could not be used.", e)
        } catch (e: Exception) {
            Timber.e(e, "Voice profile upload failed")
            Result.Error("Could not prepare voice: ${e.message}", e)
        }
    }

    /** Synthesizes [text] in the cloned voice and writes it to [target]. */
    suspend fun preview(text: String, target: File): Result<File> {
        val token = storage.getDeviceToken()
            ?: return Result.Error("Device is not enrolled")
        return try {
            val wav = api.previewVoice(text, token)
                ?: return Result.Error("No prepared voice profile")
            target.writeBytes(wav)
            Result.Success(target)
        } catch (e: Exception) {
            Timber.e(e, "Voice preview failed")
            Result.Error("Preview failed: ${e.message}", e)
        }
    }

    suspend fun revoke(): Result<Unit> {
        val token = storage.getDeviceToken()
            ?: return Result.Error("Device is not enrolled")
        return try {
            api.revokeVoiceProfile(token)
            storage.setVoiceProfileRef("")
            Timber.i("Voice profile revoked")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Voice revocation failed")
            Result.Error("Revocation failed: ${e.message}", e)
        }
    }
}
