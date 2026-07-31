package com.triplex.dialer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.triplex.dialer.TriplexDialerApp
import com.triplex.dialer.triplex.ChimeSdkBridge

/**
 * Foreground service for the AWS Chime SDK media session.
 *
 * Keeps the audio pipeline alive when the app is backgrounded.
 * Bridges Chime's WebRTC media into Triplex's PCM audio pipeline.
 */
class TriplexChimeService : Service() {

    private var chimeBridge: ChimeSdkBridge? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = TriplexDialerApp.instance
        chimeBridge = app.triplexClient.chimeBridge

        // Keep the service alive as long as there's an active call
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        chimeBridge?.disconnect()
        super.onDestroy()
    }
}
