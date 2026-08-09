package dev.triplex.dialer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.triplex.data.remote.ScreeningSession
import dev.triplex.telephony.sip.CallStateInfo
import dev.triplex.telephony.sip.TelephonyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ScreeningDecision(val wireValue: String) {
    ACCEPT("accept"),
    DECLINE("decline"),
    AGENT("agent")
}

@Singleton
class ScreeningCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyController: TelephonyController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var notifiedCallUuid: String? = null

    private val _session = MutableStateFlow<ScreeningSession?>(null)
    val session: StateFlow<ScreeningSession?> = _session.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    val agentUtterance: StateFlow<String> = telephonyController.agentUtterance
    val conversationTurns = telephonyController.conversationTurns

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            if (!telephonyController.initialize() || !telephonyController.registerSip()) {
                _message.value = "Secure Plivo SIP registration is not ready"
            }
            launch {
                telephonyController.callState.collectLatest(::onCallState)
            }
            launch {
                telephonyController.callTranscript.collectLatest { transcript ->
                    val current = _session.value ?: return@collectLatest
                    val updated = current.copy(
                        transcript = transcript,
                        updated_at = Instant.now().toString(),
                    )
                    _session.value = updated
                    showScreeningNotification(updated)
                }
            }
            launch {
                telephonyController.transcriptionStatus.collectLatest { status ->
                    _message.value = status
                }
            }
        }
    }

    private fun onCallState(state: CallStateInfo) {
        when (state) {
            is CallStateInfo.Ringing -> {
                val now = Instant.now().toString()
                val next = ScreeningSession(
                    call_uuid = "sip-${state.callerId}-${System.currentTimeMillis()}",
                    caller_number = state.callerId,
                    called_number = "Triplex SIP",
                    status = "screening",
                    transcript = "",
                    created_at = now,
                    updated_at = now,
                )
                _session.value = next
                if (next.call_uuid != notifiedCallUuid) {
                    notifiedCallUuid = next.call_uuid
                    showScreeningNotification(next)
                }
            }
            is CallStateInfo.Active -> {
                _session.value = _session.value?.copy(
                    status = "screening",
                    updated_at = Instant.now().toString(),
                )
            }
            CallStateInfo.Idle -> {
                if (_session.value != null) {
                    cancelNotification()
                    _session.value = null
                    _message.value = "Call ended"
                }
            }
            else -> Unit
        }
    }

    fun decide(decision: ScreeningDecision, automationId: String? = null) {
        val current = _session.value ?: return
        scope.launch {
            _message.value = when (decision) {
                ScreeningDecision.ACCEPT -> "Preparing secure phone connection"
                ScreeningDecision.DECLINE -> "Declining call"
                ScreeningDecision.AGENT -> "Starting Book a meeting"
            }
            when (decision) {
                ScreeningDecision.ACCEPT -> {
                    telephonyController.answer()
                    _message.value = "Call connected"
                }
                ScreeningDecision.DECLINE -> {
                    telephonyController.hangup()
                    cancelNotification()
                    _session.value = null
                    _message.value = "Call ended"
                }
                ScreeningDecision.AGENT -> {
                    _session.value = current.copy(
                        decision = automationId,
                        updated_at = Instant.now().toString(),
                    )
                    _message.value = if (
                        automationId != null && telephonyController.startAutomation(automationId)
                    ) {
                        "Automation is speaking and listening on this call"
                    } else {
                        "Automation could not start on this call"
                    }
                }
            }
        }
    }

    private fun showScreeningNotification(session: ScreeningSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Calls being screened",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Triplex has a caller's reason ready"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AgentScreeningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Triplex is screening a call")
            .setContentText(session.transcript.ifBlank { "The caller is answering now" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "triplex_screening_calls"
        const val NOTIFICATION_ID = 3110
    }
}
