package dev.triplex.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.data.repository.UserRepository
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.telephony.sip.OutboundCallCoordinator
import dev.triplex.telephony.sip.TelephonyController
import dev.triplex.voice.VoiceCloneRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground host for long-running ADB debug actions.
 *
 * [DevelopmentControlReceiver] only dispatches here so enroll/preview cannot
 * trip the broadcast ANR watchdog (~10s) on mid-tier devices.
 */
@AndroidEntryPoint
class DevelopmentControlService : Service() {
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var outboundCallCoordinator: OutboundCallCoordinator
    @Inject lateinit var telephonyController: TelephonyController
    @Inject lateinit var voiceCloneRepository: VoiceCloneRepository
    @Inject lateinit var agentConfigRepository: AgentConfigRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val action = intent?.action
        scope.launch {
            try {
                when (action) {
                    ACTION_PROVISION -> provision(intent)
                    ACTION_OUTBOUND_SMOKE -> outboundSmoke(intent!!)
                    ACTION_VOICE_CLONE_SMOKE -> voiceCloneSmoke(intent)
                    ACTION_SET_CLONED_POLICY -> setClonedPolicy()
                    ACTION_HANGUP -> {
                        telephonyController.hangup()
                        Log.i(TAG, "TRIPLEX_DEV_HANGUP PASS")
                    }
                    else -> Log.e(TAG, "TRIPLEX_DEV_CONTROL FAIL unknown_action=$action")
                }
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "TRIPLEX_DEV_CONTROL FAIL ${error::class.java.simpleName}: ${error.message}",
                )
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun provision(intent: Intent?) {
        val token = requireNotNull(intent?.getStringExtra(EXTRA_DEVICE_TOKEN))
        require(DEVICE_TOKEN.matches(token)) { "invalid device token" }
        val deviceId = requireNotNull(intent.getStringExtra(EXTRA_DEVICE_ID))
        UUID.fromString(deviceId)

        secureStorage.setDeviceToken(token)
        secureStorage.setDeviceId(deviceId)
        val credentialsReady = userRepository.syncSipCredentials()
        val deviceReady = userRepository.setDeviceReady(ready = true, mediaReady = false) is Result.Success
        check(credentialsReady) { "production SIP credentials were not returned" }
        check(deviceReady) { "production gateway did not mark the device ready" }
        Log.i(TAG, "TRIPLEX_DEV_PROVISION PASS credentials=true ready=true")
    }

    private suspend fun outboundSmoke(intent: Intent) {
        val destination = requireNotNull(intent.getStringExtra(EXTRA_DESTINATION))
        require(E164.matches(destination)) { "invalid E.164 destination" }
        check(userRepository.syncSipCredentials()) { "SIP credentials unavailable" }
        check(userRepository.setDeviceReady(ready = true, mediaReady = false) is Result.Success) {
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

    private suspend fun voiceCloneSmoke(intent: Intent?) {
        val staged = File(cacheDir, "dev_enroll_ref.wav")
        val override = intent?.getStringExtra(EXTRA_REFERENCE_WAV)
        if (override != null) {
            val src = File(override)
            check(src.isFile) { "reference wav missing at $override" }
            src.copyTo(staged, overwrite = true)
        }
        check(staged.isFile) {
            "reference wav missing at ${staged.absolutePath}; push with run-as first"
        }
        val enrollMs = measureTimeMillis {
            when (
                val prepared = voiceCloneRepository.prepareFromPhrases(
                    phraseWavs = listOf(staged),
                    consentStatement =
                        "I consent to Triplex creating a clone of my voice from this recording.",
                )
            ) {
                is Result.Success -> check(prepared.data.synthesis_ready) { "not synthesis_ready" }
                is Result.Error -> error(prepared.message)
            }
        }
        val preview = File(cacheDir, "dev_preview.wav")
        val previewMs = measureTimeMillis {
            when (val out = voiceCloneRepository.preview(VERIFY_TEXT, preview)) {
                is Result.Success -> check(out.data.length() > 44) { "empty preview wav" }
                is Result.Error -> error(out.message)
            }
        }
        Log.i(
            TAG,
            "TRIPLEX_DEV_VOICE_CLONE PASS enroll_ms=$enrollMs preview_ms=$previewMs " +
                "preview_bytes=${preview.length()} placement=LOCAL",
        )
    }

    private suspend fun setClonedPolicy() {
        when (val result = agentConfigRepository.setInboundVoicePolicy(AutomationVoicePolicy.CLONED)) {
            is Result.Success -> {
                agentConfigRepository.setOutboundDefaultVoicePolicy(AutomationVoicePolicy.CLONED)
                agentConfigRepository.setAutoAnswerAll(true)
                Log.i(TAG, "TRIPLEX_DEV_SET_CLONED PASS autoAnswer=true")
            }
            is Result.Error -> error(result.message)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Triplex debug control",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Runs ADB-triggered Triplex development checks"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Triplex debug")
        .setContentText("Running development control action")
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        const val TAG = "TriplexDevControl"
        const val ACTION_PROVISION = "dev.triplex.debug.PROVISION"
        const val ACTION_OUTBOUND_SMOKE = "dev.triplex.debug.OUTBOUND_SMOKE"
        const val ACTION_VOICE_CLONE_SMOKE = "dev.triplex.debug.VOICE_CLONE_SMOKE"
        const val ACTION_SET_CLONED_POLICY = "dev.triplex.debug.SET_CLONED_POLICY"
        const val ACTION_HANGUP = "dev.triplex.debug.HANGUP"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_REFERENCE_WAV = "reference_wav"
        private const val VERIFY_TEXT = "Yes, I have apples available today."
        private const val CHANNEL_ID = "triplex_dev_control"
        private const val NOTIFICATION_ID = 4102
        private val DEVICE_TOKEN = Regex("^device_[A-Za-z0-9_-]{32,128}$")
        private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

        fun enqueue(context: Context, intent: Intent) {
            val serviceIntent = Intent(context, DevelopmentControlService::class.java).apply {
                action = intent.action
                putExtras(intent)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
