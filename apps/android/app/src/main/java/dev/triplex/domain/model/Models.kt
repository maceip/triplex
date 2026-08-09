package dev.triplex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class UserAccount(
    val id: String,
    val email: String,
    val phone_number: String,
    val consent_given: Boolean = false,
    val consent_ts: String? = null,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class VoiceProfile(
    val id: String,
    val user_id: String,
    val version: Int,
    val encrypted_blob_ref: String,
    val encryption_key_id: String,
    val synthesis_ready: Boolean = false,
    val created_at: String
)

enum class TaskType {
    ITEM_RETURN,
    APPOINTMENT_MODIFY,
    RESERVATION_UPDATE
}

enum class TaskStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
    STOPPED
}

@Serializable
data class TaskDefinition(
    val id: String,
    val user_id: String,
    val task_type: String,
    val destination_number: String,
    val task_params: Map<String, String>,
    val status: String,
    val outcome: String? = null,
    val outcome_evidence: Map<String, String>? = null,
    val created_at: String,
    val started_at: String? = null,
    val completed_at: String? = null
)

@Serializable
data class DeviceRegistration(
    val id: String,
    val user_id: String,
    val device_token: String,
    val sip_endpoint: String,
    val push_token: String? = null,
    val ready: Boolean = false,
    val last_heartbeat: String,
    val created_at: String
)

enum class Placement {
    LOCAL,
    REMOTE_ASR,
    REMOTE_REASONING,
    REMOTE_TTS,
    REMOTE_AGENT
}
