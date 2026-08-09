package dev.triplex.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.data.repository.UserRepository
import dev.triplex.telephony.sip.OutboundCallCoordinator
import dev.triplex.telephony.sip.TelephonyController
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** ADB-only development control surface. It is absent from release APKs. */
@AndroidEntryPoint
class DevelopmentControlReceiver : BroadcastReceiver() {
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var outboundCallCoordinator: OutboundCallCoordinator
    @Inject lateinit var telephonyController: TelephonyController

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PROVISION -> provision(intent)
                    ACTION_OUTBOUND_SMOKE -> outboundSmoke(intent)
                    ACTION_HANGUP -> {
                        telephonyController.hangup()
                        Log.i(TAG, "TRIPLEX_DEV_HANGUP PASS")
                    }
                    else -> Log.e(TAG, "TRIPLEX_DEV_CONTROL FAIL unknown_action")
                }
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "TRIPLEX_DEV_CONTROL FAIL ${error::class.java.simpleName}: ${error.message}",
                )
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun provision(intent: Intent) {
        val token = requireNotNull(intent.getStringExtra(EXTRA_DEVICE_TOKEN))
        require(DEVICE_TOKEN.matches(token)) { "invalid device token" }
        val deviceId = requireNotNull(intent.getStringExtra(EXTRA_DEVICE_ID))
        UUID.fromString(deviceId)

        secureStorage.setDeviceToken(token)
        secureStorage.setDeviceId(deviceId)
        val credentialsReady = userRepository.syncSipCredentials()
        val deviceReady = userRepository.setDeviceReady(true) is Result.Success
        check(credentialsReady) { "production SIP credentials were not returned" }
        check(deviceReady) { "production gateway did not mark the device ready" }
        Log.i(TAG, "TRIPLEX_DEV_PROVISION PASS credentials=true ready=true")
    }

    private suspend fun outboundSmoke(intent: Intent) {
        val destination = requireNotNull(intent.getStringExtra(EXTRA_DESTINATION))
        require(E164.matches(destination)) { "invalid E.164 destination" }
        check(userRepository.syncSipCredentials()) { "SIP credentials unavailable" }
        check(userRepository.setDeviceReady(true) is Result.Success) {
            "device readiness update failed"
        }

        val task = when (
            val created = taskRepository.createTask(
                taskType = "item_return",
                destinationNumber = destination,
                taskParams = mapOf(
                    "product" to "Samsung phone",
                    "order_number" to "TRIPLEX TEST ORDER",
                    "return_reason" to "the item arrived damaged",
                    "desired_outcome" to "a return authorization and refund",
                ),
            )
        ) {
            is Result.Success -> created.data
            is Result.Error -> error(created.message)
        }
        when (val started = outboundCallCoordinator.startTask(task.id)) {
            is Result.Success -> Log.i(
                TAG,
                "TRIPLEX_DEV_OUTBOUND PASS task=${started.data.id} call_started=true",
            )
            is Result.Error -> error(started.message)
        }
    }

    private companion object {
        const val TAG = "TriplexDevControl"
        const val ACTION_PROVISION = "dev.triplex.debug.PROVISION"
        const val ACTION_OUTBOUND_SMOKE = "dev.triplex.debug.OUTBOUND_SMOKE"
        const val ACTION_HANGUP = "dev.triplex.debug.HANGUP"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DESTINATION = "destination"
        val DEVICE_TOKEN = Regex("^device_[A-Za-z0-9_-]{32,128}$")
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}
