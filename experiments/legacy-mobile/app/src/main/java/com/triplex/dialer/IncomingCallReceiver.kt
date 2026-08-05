package com.triplex.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.triplex.dialer.model.CallData
import com.triplex.dialer.service.CallRepository
import com.triplex.dialer.triplex.ChimeSdkBridge

/**
 * Receives incoming call notifications from Chime SIP media app
 * and bridges them into the telecom stack.
 *
 * Mirrors AndroidX reference app's IncomingCallReceiver.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callerNumber = intent.getStringExtra("caller_number") ?: return
        val meetingId = intent.getStringExtra("meeting_id") ?: return

        val call = CallData(
            dialedNumber = callerNumber,
            isIncoming = true,
        )

        CallRepository.registerCall(call)

        // Forward to TriplexClient via TelecomVoipService
        val intent2 = Intent(context, com.triplex.dialer.service.TelecomVoipService::class.java)
        intent2.putExtra("call_id", call.id)
        intent2.putExtra("meeting_id", meetingId)
        context.startService(intent2)
    }
}
