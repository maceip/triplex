package dev.triplex.data.repository

import dev.triplex.data.local.SecureStorage
import dev.triplex.data.remote.GatewayApi
import dev.triplex.data.remote.OutboundRouteGrant
import dev.triplex.data.remote.RegisterDeviceRequest
import dev.triplex.domain.model.DeviceRegistration
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.domain.model.UserAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val exception: Throwable? = null) : Result<Nothing>()
}

@Singleton
class UserRepository @Inject constructor(
    private val api: GatewayApi,
    private val storage: SecureStorage
) {
    private var _currentUser: UserAccount? = null
    val currentUser: UserAccount? get() = _currentUser

    val deviceToken: Flow<String?> = storage.deviceToken

    suspend fun register(email: String, phoneNumber: String): Result<UserAccount> {
        return try {
            val enrollment = api.registerUser(email, phoneNumber)
            _currentUser = enrollment.user

            // The gateway mints the device token; it is the only token its
            // X-Device-Token validation will ever accept.
            storage.setDeviceToken(enrollment.device_token)
            storage.setDeviceId(enrollment.user.id)

            Timber.i("User registered: ${enrollment.user.id}")
            Result.Success(enrollment.user)
        } catch (e: Exception) {
            Timber.e(e, "Registration failed")
            Result.Error("Registration failed: ${e.message}", e)
        }
    }

    /**
     * Pulls provider SIP credentials from the gateway into SecureStorage.
     * Returns false (without error) when none are provisioned yet.
     */
    suspend fun syncSipCredentials(): Boolean {
        val token = storage.getDeviceToken() ?: return false
        return try {
            val credentials = api.getSipCredentials(token)
            if (credentials == null) {
                Timber.i("No SIP credentials provisioned yet")
                return false
            }
            storage.setPlivoUsername(credentials.username)
            storage.setPlivoPassword(credentials.password)
            Timber.i("SIP credentials synced for %s", credentials.domain)
            true
        } catch (e: Exception) {
            Timber.w(e, "SIP credential sync failed")
            false
        }
    }

    suspend fun registerDevice(sipEndpoint: String, pushToken: String?): Result<DeviceRegistration> {
        val token = storage.getDeviceToken() ?: return Result.Error("No device token")
        
        return try {
            val device = api.registerDevice(sipEndpoint, pushToken, token)
            storage.setDeviceId(device.id)
            Timber.i("Device registered: ${device.id}")
            Result.Success(device)
        } catch (e: Exception) {
            Timber.e(e, "Device registration failed")
            Result.Error("Device registration failed: ${e.message}", e)
        }
    }

    /**
     * @param mediaReady whether this phone will carry the caller's audio. The
     *   gateway bridges inbound calls to the device only when this is set, so
     *   it is passed explicitly at every call site rather than defaulted —
     *   a caller silently connected to a device that cannot take media hears
     *   an answered call and then nothing.
     */
    suspend fun setDeviceReady(ready: Boolean, mediaReady: Boolean): Result<Unit> {
        val token = storage.getDeviceToken() ?: return Result.Error("No device token")

        return try {
            api.setDeviceReady(ready, mediaReady, token)
            Timber.i("Device ready status: ready=$ready mediaReady=$mediaReady")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set device ready")
            Result.Error("Failed to set device ready: ${e.message}", e)
        }
    }

    suspend fun getDeviceReady(): Boolean {
        val token = storage.getDeviceToken() ?: return false
        return try {
            val status = api.getDeviceStatus(token)
            status.ready
        } catch (e: Exception) {
            Timber.e(e, "Failed to get device status")
            false
        }
    }

    fun isRegistered(): Boolean {
        return storage.getDeviceToken() != null
    }
}

@Singleton
class TaskRepository @Inject constructor(
    private val api: GatewayApi,
    private val storage: SecureStorage
) {
    private val _activeTask = MutableStateFlow<TaskDefinition?>(null)
    val activeTask: Flow<TaskDefinition?> = _activeTask.asStateFlow()

    suspend fun createTask(
        taskType: String,
        destinationNumber: String,
        taskParams: Map<String, String>
    ): Result<TaskDefinition> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        
        return try {
            val task = api.createTask(taskType, destinationNumber, taskParams, token)
            Timber.i("Task created: ${task.id}")
            Result.Success(task)
        } catch (e: Exception) {
            Timber.e(e, "Task creation failed")
            Result.Error("Failed to create task: ${e.message}", e)
        }
    }

    suspend fun getTasks(status: String? = null): Result<List<TaskDefinition>> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        
        return try {
            val tasks = api.getTasks(status, token)
            Result.Success(tasks)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get tasks")
            Result.Error("Failed to load tasks: ${e.message}", e)
        }
    }

    suspend fun startTask(taskId: String): Result<TaskDefinition> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        
        return try {
            val task = api.startTask(taskId, token)
            _activeTask.value = task
            Timber.i("Task started: $taskId")
            Result.Success(task)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start task")
            Result.Error("Failed to start task: ${e.message}", e)
        }
    }

    suspend fun authorizeOutbound(
        taskId: String,
        destinationNumber: String
    ): Result<OutboundRouteGrant> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")

        return try {
            Result.Success(api.authorizeOutbound(taskId, destinationNumber, token))
        } catch (e: Exception) {
            Timber.e(e, "Outbound authorization failed")
            Result.Error("Failed to authorize outbound call: ${e.message}", e)
        }
    }

    suspend fun stopTask(taskId: String): Result<TaskDefinition> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        
        return try {
            val task = api.stopTask(taskId, token)
            _activeTask.value = null
            Timber.i("Task stopped: $taskId")
            Result.Success(task)
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop task")
            Result.Error("Failed to stop task: ${e.message}", e)
        }
    }

    suspend fun getTask(taskId: String): Result<TaskDefinition> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        
        return try {
            val task = api.getTask(taskId, token)
            Result.Success(task)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get task")
            Result.Error("Failed to load task: ${e.message}", e)
        }
    }
}
