package dev.triplex.telephony.sip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.MainActivity
import dev.triplex.dialer.ScreeningCoordinator
import javax.inject.Inject

@AndroidEntryPoint
class TriplexSipService : Service() {
    @Inject lateinit var screeningCoordinator: ScreeningCoordinator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        screeningCoordinator.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        screeningCoordinator.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Triplex phone connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Triplex ready to receive calls"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_action_call)
        .setContentTitle("Triplex phone is ready")
        .setContentText("Connected for incoming calls")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setSilent(true)
        .build()

    private companion object {
        const val CHANNEL_ID = "triplex_sip_runtime"
        const val NOTIFICATION_ID = 4101
    }
}
