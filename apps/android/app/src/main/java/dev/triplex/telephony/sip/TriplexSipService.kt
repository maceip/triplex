package dev.triplex.telephony.sip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.BuildConfig
import dev.triplex.dialer.CallNotificationController
import dev.triplex.dialer.DialerActivity
import dev.triplex.dialer.ScreeningCoordinator
import dev.triplex.nativebridge.agent.AgentBridge
import dev.triplex.nativebridge.runtime.NativeRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Owns the native runtime and the SIP registration for as long as the process
 * lives.
 *
 * This lifecycle deliberately does **not** belong to an Activity. A configuration
 * change destroys and recreates an Activity, so hanging `runtime.shutdown()` and
 * `telephonyController.shutdown()` off `Activity.onDestroy` meant that rotating
 * the device during a live SIP call tore that call down. The service is started
 * from [dev.triplex.TriplexApplication] and runs foreground, so it outlives every
 * screen and survives rotation.
 */
@AndroidEntryPoint
class TriplexSipService : Service() {
    @Inject lateinit var screeningCoordinator: ScreeningCoordinator

    @Inject lateinit var callNotificationController: CallNotificationController

    @Inject lateinit var telephonyController: TelephonyController

    @Inject lateinit var agentBridge: AgentBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runtimeManager by lazy {
        NativeRuntimeManager.getInstance(applicationContext)
    }

    /**
     * Debug-only loopback driver:
     * `adb shell am broadcast -a dev.triplex.DEMO_TURN [--ez barge_in true]`
     */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            agentBridge.runDemoTurn(
                bargeIn = intent?.getBooleanExtra("barge_in", false) == true,
            )
        }
    }

    private var demoReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // Idempotent, and also called by TriplexInCallService: whichever stack
        // comes up first starts the one call-notification renderer.
        callNotificationController.start()
        registerDemoReceiver()
        startRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // start() is a no-op while its poll job is alive, so re-delivery is safe.
        screeningCoordinator.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (demoReceiverRegistered) {
            runCatching { unregisterReceiver(demoReceiver) }
            demoReceiverRegistered = false
        }
        scope.cancel()
        agentBridge.stop()
        telephonyController.shutdown()
        runtimeManager.shutdown()
        Timber.i("SIP service destroyed, native resources released")
        super.onDestroy()
    }

    /**
     * Brings the native runtime up, then hands off to [ScreeningCoordinator],
     * which performs the SIP credential sync and registration itself.
     *
     * Ordering matters: the runtime must be ready before SIP registration is
     * attempted. [NativeRuntimeManager.initialize] is not idempotent, so it is
     * called exactly once here rather than on every `onStartCommand`.
     */
    private fun startRuntime() {
        scope.launch {
            try {
                if (!runtimeManager.initialize()) {
                    Timber.e("Failed to initialize native runtime")
                    return@launch
                }
                if (!runtimeManager.isReady()) {
                    Timber.w("Native runtime reported not ready after initialize")
                    return@launch
                }
                Timber.i("Native runtime ready")
                screeningCoordinator.start()
            } catch (e: Exception) {
                Timber.e(e, "Native runtime initialization failed")
            }
        }
    }

    private fun registerDemoReceiver() {
        if (!BuildConfig.DEBUG) return
        ContextCompat.registerReceiver(
            this,
            demoReceiver,
            IntentFilter("dev.triplex.DEMO_TURN"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        demoReceiverRegistered = true
    }

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
                Intent(this, DialerActivity::class.java).apply {
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
