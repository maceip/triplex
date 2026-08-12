package dev.triplex.dialer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that posts call notifications (reskin.md §2.3).
 *
 * Replaces `DialerCallNotificationManager` (Telecom) and the screening
 * notification `ScreeningCoordinator` used to build itself, so a SIM call and a
 * SIP call produce the same notification with the same actions. It renders
 * [CallSessionRepository.session] rather than being pushed at from call
 * callbacks, which is what removes the duplicate-notification problem.
 */
@Singleton
class CallNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CallSessionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)

    @Volatile
    private var callUiVisible = false

    @Volatile
    private var current: CallSession? = null

    /** Idempotent: both the SIP service and the in-call service call it. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        createChannels()
        scope.launch {
            repository.session.collect { session ->
                current = session
                render(session)
            }
        }
        scope.launch {
            repository.events.collect { event ->
                if (event is CallLifecycleEvent.Ended && event.missed) showMissed(event.session)
            }
        }
    }

    /**
     * While the in-app call surface is on screen the notification would be a
     * duplicate of it, so it is withheld and restored on the way out.
     */
    fun setCallUiVisible(visible: Boolean) {
        callUiVisible = visible
        render(current)
    }

    private fun render(session: CallSession?) {
        if (session == null || callUiVisible || session.phase == CallPhase.ENDED) {
            cancel()
            return
        }
        if (!canNotify()) return

        when (session.phase) {
            CallPhase.RINGING, CallPhase.SCREENING -> showIncoming(session)
            else -> showOngoing(session)
        }
    }

    private fun showIncoming(session: CallSession) {
        val content = activityIntent()
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(session.party.displayName)
            .setContentText(session.statusLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(session),
                    action(CallActionReceiver.ACTION_DECLINE, session.id, 1),
                    action(CallActionReceiver.ACTION_ANSWER, session.id, 2),
                )
            )
            .apply {
                // Gated on the hand-off capability alone, not on agentHasMedia.
                // A notification action carries no automation id — there is no
                // picker in a notification — and a leg that can only be handed
                // to a *named* automation answers an id-less hand-off with
                // "Automation could not start on this call". Offering the button
                // there means offering a failure from the lock screen.
                if (session.capabilities.canDeflectToAgent) {
                    addAction(
                        android.R.drawable.ic_menu_send,
                        "Agent",
                        action(CallActionReceiver.ACTION_AGENT, session.id, 4),
                    )
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun showOngoing(session: CallSession) {
        val connectedAt = session.connectedAtMs
        val notification = NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(session.party.displayName)
            .setContentText(session.statusLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityIntent())
            .setUsesChronometer(connectedAt != null)
            .setWhen(connectedAt ?: System.currentTimeMillis())
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(session),
                    action(CallActionReceiver.ACTION_HANG_UP, session.id, 3),
                )
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun showMissed(session: CallSession) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(session.party.displayName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(session.party.number))
            .build()
        NotificationManagerCompat.from(context).notify(MISSED_NOTIFICATION_ID, notification)
    }

    private fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun person(session: CallSession): Person = Person.Builder()
        .setName(session.party.displayName.ifBlank { "Unknown caller" })
        .setUri(session.party.number.takeIf(String::isNotBlank)?.let { "tel:$it" })
        .setImportant(true)
        .build()

    private fun activityIntent(number: String = ""): PendingIntent {
        val intent = Intent(context, DialerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (number.isNotBlank()) {
                action = Intent.ACTION_DIAL
                data = Uri.fromParts("tel", number, null)
            }
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(action: String, sessionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .putExtra(CallActionReceiver.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val incoming = NotificationChannel(
            INCOMING_CHANNEL_ID,
            "Incoming and active calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Triplex Phone call controls"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build(),
            )
        }
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "Active calls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Triplex Phone controls during a call"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        val missed = NotificationChannel(
            MISSED_CHANNEL_ID,
            "Missed calls",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Calls you missed in Triplex Phone"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(incoming, ongoing, missed))
        // Screening calls no longer get a channel of their own; drop the one
        // installed by earlier builds so Settings stops listing it.
        manager.deleteNotificationChannel(RETIRED_SCREENING_CHANNEL_ID)
    }

    private companion object {
        const val INCOMING_CHANNEL_ID = "triplex_incoming_calls"
        const val ONGOING_CHANNEL_ID = "triplex_active_calls"
        const val MISSED_CHANNEL_ID = "triplex_missed_calls"
        const val RETIRED_SCREENING_CHANNEL_ID = "triplex_screening_calls"
        const val NOTIFICATION_ID = 3108
        const val MISSED_NOTIFICATION_ID = 3109
    }
}

/**
 * The single receiver behind every call notification action, for both stacks.
 *
 * It carries the session id so a notification for a backgrounded call cannot
 * act on whichever call has since become primary.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: CallSessionRepository

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val callIntent = when (intent.action) {
            ACTION_ANSWER -> CallIntent.Answer
            ACTION_DECLINE -> CallIntent.Decline
            ACTION_AGENT -> CallIntent.HandOffToAgent(automationId = null)
            ACTION_HANG_UP -> CallIntent.HangUp
            else -> return
        }
        repository.dispatch(callIntent, sessionId)

        if (callIntent == CallIntent.Answer || callIntent is CallIntent.HandOffToAgent) {
            context.startActivity(
                Intent(context, DialerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.triplex.dialer.ANSWER"
        const val ACTION_DECLINE = "dev.triplex.dialer.DECLINE"
        const val ACTION_AGENT = "dev.triplex.dialer.AGENT"
        const val ACTION_HANG_UP = "dev.triplex.dialer.HANG_UP"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
