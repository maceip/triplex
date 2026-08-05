package com.triplex.dialer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * [Deprecated] AWS Chime SDK media session service.
 * Replaced by Plivo WebSocket bridge via TriplexClient.
 * Keep this file for reference; service is disabled.
 */
class TriplexChimeService : Service() {

    // private var chimeBridge: ChimeSdkBridge? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // chimeBridge = app.triplexClient.chimeBridge
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        // chimeBridge?.disconnect()
        super.onDestroy()
    }
}
