package dev.triplex.data.remote

import dev.triplex.domain.model.DeviceRegistration
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.domain.model.UserAccount
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RegisterRequest(
    val email: String,
    val phone_number: String
)

@Serializable
data class EnrollmentResponse(
    val user: UserAccount,
    val device_token: String
)

@Serializable
data class SipCredentials(
    val provider: String,
    val username: String,
    val password: String,
    val domain: String,
    val realm: String? = null
)

@Serializable
data class VoiceProfileStatus(
    val profile_id: String? = null,
    val synthesis_ready: Boolean = false,
    val placement: String = "REMOTE_TTS",
    val placement_reason: String = "",
    val reference_seconds: Int? = null,
    val consent_recorded_at: String? = null
)

@Serializable
data class VoicePreviewRequest(val text: String)

/** Carries a user-facing reason a recording could not become a voice. */
class VoicePreparationException(message: String) : Exception(message)

private val DETAIL_PATTERN = Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

@Serializable
data class RegisterDeviceRequest(
    val sip_endpoint: String,
    val push_token: String? = null
)

@Serializable
data class CreateTaskRequest(
    val task_type: String,
    val destination_number: String,
    val task_params: Map<String, String>
)

@Singleton
class GatewayApi @Inject constructor(
    private val client: HttpClient
) {
    suspend fun registerUser(email: String, phoneNumber: String): EnrollmentResponse {
        return client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, phoneNumber))
        }.body()
    }

    suspend fun getSipCredentials(deviceToken: String): SipCredentials? {
        val response = client.get("/devices/sip-credentials") {
            header("X-Device-Token", deviceToken)
        }
        if (response.status != HttpStatusCode.OK) {
            return null
        }
        return response.body()
    }

    suspend fun registerDevice(
        sipEndpoint: String,
        pushToken: String?,
        deviceToken: String
    ): DeviceRegistration {
        return client.post("/devices/register") {
            contentType(ContentType.Application.Json)
            header("X-Device-Token", deviceToken)
            setBody(RegisterDeviceRequest(sipEndpoint, pushToken))
        }.body()
    }

    suspend fun setDeviceReady(ready: Boolean, deviceToken: String) {
        client.post("/devices/ready") {
            header("X-Device-Token", deviceToken)
            url { parameters.append("ready", ready.toString()) }
        }
    }

    suspend fun getDeviceStatus(deviceToken: String): Map<String, String> {
        return client.get("/devices/status") {
            header("X-Device-Token", deviceToken)
        }.body()
    }

    suspend fun createTask(
        taskType: String,
        destinationNumber: String,
        taskParams: Map<String, String>,
        deviceToken: String
    ): TaskDefinition {
        return client.post("/tasks") {
            contentType(ContentType.Application.Json)
            header("X-Device-Token", deviceToken)
            setBody(CreateTaskRequest(taskType, destinationNumber, taskParams))
        }.body()
    }

    suspend fun getTasks(status: String?, deviceToken: String): List<TaskDefinition> {
        return client.get("/tasks") {
            header("X-Device-Token", deviceToken)
            status?.let { requestedStatus ->
                url { parameters.append("status", requestedStatus) }
            }
        }.body()
    }

    suspend fun getTask(taskId: String, deviceToken: String): TaskDefinition {
        return client.get("/tasks/$taskId") {
            header("X-Device-Token", deviceToken)
        }.body()
    }

    suspend fun startTask(taskId: String, deviceToken: String): TaskDefinition {
        return client.post("/tasks/$taskId/start") {
            header("X-Device-Token", deviceToken)
        }.body()
    }

    suspend fun stopTask(taskId: String, deviceToken: String): TaskDefinition {
        return client.post("/tasks/$taskId/stop") {
            header("X-Device-Token", deviceToken)
        }.body()
    }

    suspend fun health(): Map<String, String> {
        return client.get("/health").body()
    }

    /**
     * Uploads the consent recording (which is also the cloning reference)
     * and returns the prepared profile status.
     */
    suspend fun uploadVoiceProfile(
        consentStatement: String,
        referenceSeconds: Int,
        wavBytes: ByteArray,
        deviceToken: String
    ): VoiceProfileStatus {
        val response = client.submitFormWithBinaryData(
            url = "/voice/profile",
            formData = formData {
                append("consent_statement", consentStatement)
                append("reference_seconds", referenceSeconds.toString())
                append("reference", wavBytes, Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"reference.wav\"")
                })
            }
        ) {
            header("X-Device-Token", deviceToken)
        }
        if (response.status != HttpStatusCode.OK) {
            // The server verifies a profile can actually speak before calling
            // it ready, and its rejection text tells the user what to change.
            throw VoicePreparationException(extractDetail(response.bodyAsText()))
        }
        return response.body()
    }

    /** Pulls the human-readable part out of a FastAPI error body. */
    private fun extractDetail(body: String): String {
        val detail = DETAIL_PATTERN.find(body)?.groupValues?.get(1) ?: return body
        // The gateway forwards the voice service's body verbatim, so a
        // rejection can arrive wrapped one level deeper.
        return DETAIL_PATTERN.find(detail)?.groupValues?.get(1)
            ?: detail.replace("\\\"", "\"")
    }

    suspend fun getVoiceProfile(deviceToken: String): VoiceProfileStatus {
        return client.get("/voice/profile") {
            header("X-Device-Token", deviceToken)
        }.body()
    }

    /** Returns cloned-voice WAV bytes, or null when no profile is ready. */
    suspend fun previewVoice(text: String, deviceToken: String): ByteArray? {
        val response = client.post("/voice/preview") {
            contentType(ContentType.Application.Json)
            header("X-Device-Token", deviceToken)
            setBody(VoicePreviewRequest(text))
        }
        if (response.status != HttpStatusCode.OK) {
            return null
        }
        return response.body()
    }

    suspend fun revokeVoiceProfile(deviceToken: String): VoiceProfileStatus {
        return client.delete("/voice/profile") {
            header("X-Device-Token", deviceToken)
        }.body()
    }
}
