package com.triplex.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.triplex.dialer.model.CallData

/**
 * Manages notifications for active calls.
 * Mirrors AndroidX reference app's CallNotificationManager.
 */
object CallNotificationManager {

    private const val CHANNEL_ID = "triplex-call-channel"
    private var notificationIdCounter = 1000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Triplex Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Active call notifications"
            setShowBadge(true)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun showCallNotification(context: Context, call: CallData): Int {
        val notificationId = notificationIdCounter++
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(call.displayLabel())
            .setContentText(call.isIncoming -> "Incoming call" else "Call in progress")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())

        NotificationIdTracker.registerNotificationId(notificationId)
        return notificationId
    }

    fun cancelCallNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
        NotificationIdTracker.removeNotificationId(notificationId)
    }
}
