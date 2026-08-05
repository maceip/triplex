package com.triplex.dialer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.telecom.CallsManager
import com.triplex.dialer.TriplexDialerApp
import com.triplex.dialer.model.CallData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * AndroidX core-telecom VOIP service placeholder.
 *
 * Bridges Triplex voice calls into the Android telecom framework so the system
 * UI (dialer, status bar, lock screen) treats them as real calls.
 *
 * Note: AndroidX Telecom 1.0.0 does not include VoipService/VoipServiceImpl.
 * These were added in a later version. This implementation uses the available
 * CallsManager API directly. When the correct artifact version is available,
 * migrate to VoipServiceImpl for full lifecycle callbacks.
 */
class TelecomVoipService : Service() {

    override fun onBind(intent: Intent): IBinder? {
        // Placeholder: CallsManager integration TBD
        // In production, this would register a call via CallsManager.addCall()
        return null
    }

    override fun onUnbind(intent: Intent): Boolean {
        return false
    }
}

/**
 * In-memory call registry for telecom service bridge.
 */
object CallRepository {
    private val calls = mutableMapOf<String, CallData>()

    private val _callFlow = MutableSharedFlow<CallData>(replay = 1)
    val callFlow: SharedFlow<CallData> = _callFlow

    fun registerCall(call: CallData) {
        calls[call.id] = call
        _callFlow.tryEmit(call)
    }

    fun resolveCall(callId: String): CallData? = calls[callId]

    fun removeCall(callId: String) {
        calls.remove(callId)
    }

    fun activeCalls(): List<CallData> = calls.values.toList()
}
