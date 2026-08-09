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
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

object DialerCallNotificationManager {
    private const val INCOMING_CHANNEL_ID = "triplex_incoming_calls"
    private const val ONGOING_CHANNEL_ID = "triplex_active_calls"
    private const val MISSED_CHANNEL_ID = "triplex_missed_calls"
    private const val NOTIFICATION_ID = 3108
    private const val MISSED_NOTIFICATION_ID = 3109
    @Volatile private var callUiVisible = false

    fun setCallUiVisible(context: Context, visible: Boolean) {
        callUiVisible = visible
        if (visible) {
            cancel(context)
            return
        }

        val state = DialerCallStore.state.value
        when {
            state.isRinging -> showIncoming(context, state)
            state.hasCall -> showOngoing(context, state)
        }
    }

    fun createChannel(context: Context) {
        val incomingChannel = NotificationChannel(
            INCOMING_CHANNEL_ID,
            "Incoming and active calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Triplex Phone call controls"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
        }
        val ongoingChannel = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "Active calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Triplex Phone controls during a call"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        val missedChannel = NotificationChannel(
            MISSED_CHANNEL_ID,
            "Missed calls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Calls you missed in Triplex Phone"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(incomingChannel, ongoingChannel, missedChannel)
        )
    }

    fun showIncoming(context: Context, state: DialerCallUiState) {
        if (callUiVisible) {
            cancel(context)
            return
        }
        if (!canNotify(context)) return
        createChannel(context)

        val caller = caller(state)
        val content = activityIntent(context)
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(state.displayName.ifBlank { state.number.ifBlank { "Incoming call" } })
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    actionIntent(context, CallNotificationActionReceiver.ACTION_DECLINE, 1),
                    actionIntent(context, CallNotificationActionReceiver.ACTION_ANSWER, 2)
                )
            )
            .addAction(
                android.R.drawable.ic_menu_send,
                    "Agent",
                actionIntent(context, CallNotificationActionReceiver.ACTION_AGENT, 4)
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun showOngoing(context: Context, state: DialerCallUiState) {
        if (!canNotify(context)) return
        createChannel(context)

        val notification = NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(state.displayName.ifBlank { state.number.ifBlank { "Phone call" } })
            .setContentText(state.status)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityIntent(context))
            .setUsesChronometer(state.connectTimeMillis > 0L)
            .setWhen(state.connectTimeMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Hang up",
                actionIntent(context, CallNotificationActionReceiver.ACTION_HANG_UP, 3)
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun showMissed(context: Context, state: DialerCallUiState) {
        if (!canNotify(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(state.displayName.ifBlank { state.number.ifBlank { "Unknown caller" } })
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, state.number))
            .build()
        NotificationManagerCompat.from(context).notify(MISSED_NOTIFICATION_ID, notification)
    }

    private fun caller(state: DialerCallUiState): Person {
        return Person.Builder()
            .setName(state.displayName.ifBlank { state.number.ifBlank { "Unknown caller" } })
            .setUri(state.number.takeIf(String::isNotBlank)?.let { "tel:$it" })
            .setImportant(true)
            .build()
    }

    private fun activityIntent(context: Context, number: String = ""): PendingIntent {
        val intent = Intent(context, DialerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (number.isNotBlank()) {
                action = Intent.ACTION_DIAL
                data = android.net.Uri.fromParts("tel", number, null)
            }
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallNotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

class CallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> {
                TriplexInCallService.answerCurrent()
                context.startActivity(
                    Intent(context, DialerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
            ACTION_DECLINE -> TriplexInCallService.declineCurrent()
            ACTION_AGENT -> {
                TriplexInCallService.transferCurrentToAgent()
                context.startActivity(
                    Intent(context, AgentScreeningActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
            ACTION_HANG_UP -> TriplexInCallService.disconnectCurrent()
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.triplex.dialer.ANSWER"
        const val ACTION_DECLINE = "dev.triplex.dialer.DECLINE"
        const val ACTION_AGENT = "dev.triplex.dialer.AGENT"
        const val ACTION_HANG_UP = "dev.triplex.dialer.HANG_UP"
    }
}
