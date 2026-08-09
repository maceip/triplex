package dev.triplex.telephony.sip

import android.os.SystemClock
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.telephony.plivo.AuthorizedSipRoute
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commits the side-effecting outbound sequence in one place.
 *
 * The gateway serializes task activation and grant issuance. Only after the
 * one-use grant is returned do we ask PJSIP to send an INVITE carrying it.
 */
@Singleton
class OutboundCallCoordinator @Inject constructor(
    private val taskRepository: TaskRepository,
    private val telephonyController: TelephonyController,
) {
    suspend fun startTask(taskId: String): Result<TaskDefinition> {
        val task = when (val fetched = taskRepository.getTask(taskId)) {
            is Result.Success -> fetched.data
            is Result.Error -> return fetched
        }
        val activeTask = when (task.status.lowercase()) {
            "pending" -> when (val started = taskRepository.startTask(taskId)) {
                is Result.Success -> started.data
                is Result.Error -> return started
            }
            "active" -> task
            else -> return Result.Error("Task cannot place a call in its current state")
        }
        val grant = when (
            val authorized = taskRepository.authorizeOutbound(
                taskId = activeTask.id,
                destinationNumber = activeTask.destination_number,
            )
        ) {
            is Result.Success -> authorized.data
            is Result.Error -> return authorized
        }

        val route = AuthorizedSipRoute(
            taskId = grant.task_id,
            sipUri = grant.sip_uri,
            headerName = grant.header_name,
            headerValue = grant.token,
            expiresAtElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos() +
                grant.expires_in_seconds.toLong() * NANOS_PER_SECOND,
        )
        if (!telephonyController.placeAuthorizedCall(
                route,
                activeTask.destination_number,
                activeTask,
            )
        ) {
            return Result.Error("The secure SIP endpoint could not place the authorized call")
        }
        return Result.Success(activeTask)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
