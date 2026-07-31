package com.triplex.dialer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.telecom.CallsManager
import androidx.core.telecom.VoipService
import androidx.core.telecom.VoipServiceImpl
import com.triplex.dialer.TriplexDialerApp
import com.triplex.dialer.model.CallData
import com.triplex.dialer.model.CallState

/**
 * AndroidX core-telecom VOIP service.
 *
 * Bridges Triplex voice calls into the Android telecom framework so the system
 * UI (dialer, status bar, lock screen) treats them as real calls.
 */
class TelecomVoipService : Service() {

    private lateinit var voipServiceImpl: VoipServiceImpl

    override fun onCreate() {
        super.onCreate()
        voipServiceImpl = VoipServiceImpl(this).also { impl ->
            impl.onAnswer { call ->
                val callData = CallRepository.resolveCall(call.id)
                val app = TriplexDialerApp.instance
                app.triplexClient.handleIncomingCall(callData)
            }

            impl.onDecline { call ->
                val callData = CallRepository.resolveCall(call.id)
                callData?.let { TriplexDialerApp.instance.triplexClient.endCall(it) }
            }

            impl.onDisconnect { call ->
                val callData = CallRepository.resolveCall(call.id)
                callData?.let { TriplexDialerApp.instance.triplexClient.endCall(it) }
            }

            impl.onMute { call, muted ->
                val callData = CallRepository.resolveCall(call.id)
                callData?.let { TriplexDialerApp.instance.triplexClient.setMuted(it, muted) }
            }

            impl.onHold { call, isLocalHold ->
                val callData = CallRepository.resolveCall(call.id)
                callData?.let { TriplexDialerApp.instance.triplexClient.setHeld(it, isLocalHold) }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return CallsManager.bind(this, voipServiceImpl)
    }

    override fun onUnbind(intent: Intent): Boolean {
        return false
    }
}

/**
 * In-memory call registry for telecom service bridge.
 * Mirrors the AndroidX reference app's CallRepository.
 */
object CallRepository {
    private val calls = mutableMapOf<String, CallData>()

    fun registerCall(call: CallData) {
        calls[call.id] = call
    }

    fun resolveCall(callId: String): CallData? = calls[callId]

    fun removeCall(callId: String) {
        calls.remove(callId)
    }

    fun activeCalls(): List<CallData> = calls.values.toList()
}
