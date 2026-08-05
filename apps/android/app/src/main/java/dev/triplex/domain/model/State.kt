package dev.triplex.domain.model

data class EnrollmentState(
    val email: String = "",
    val phoneNumber: String = "",
    val user: UserAccount? = null,
    val device: DeviceRegistration? = null,
    val deviceReady: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

data class TaskState(
    val tasks: List<TaskDefinition> = emptyList(),
    val activeTask: TaskDefinition? = null,
    val loading: Boolean = false,
    val error: String? = null
)

data class CallState(
    val callActive: Boolean = false,
    val callUuid: String? = null,
    val destination: String? = null,
    val placement: Placement = Placement.LOCAL,
    val duration: Int = 0,
    val status: String? = null
)

sealed class CallEvent {
    data class Incoming(val callerId: String, val callUuid: String) : CallEvent()
    data class OutboundStarted(val destination: String, val callUuid: String) : CallEvent()
    data class Ended(val callUuid: String, val duration: Int) : CallEvent()
    data class Interrupted(val reason: String) : CallEvent()
    data class PlacementChanged(val placement: Placement) : CallEvent()
}

enum class AgentStatus {
    IDLE,
    PREPARING,
    READY,
    ACTIVE,
    ERROR
}
