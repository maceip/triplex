package com.triplex.dialer.triplex

import android.content.Context
import com.triplex.dialer.Constants
import com.triplex.dialer.model.ChimeConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Plivo WebSocket bridge — replaces AWS Chime SDK bridge.
 *
 * Architecture:
 *   1. Plivo receives a call and POSTs to /answer -> returns <Stream> XML
 *   2. Plivo opens WebSocket to wss://bridge.public.computer/stream
 *   3. Our server relays audio between Plivo and this Android app
 *   4. This class connects to wss://bridge.public.computer/android/{call_id}
 *      and exchanges audio frames
 *
 * Audio format: mu-law 8000 Hz, base64-encoded
 */

// ─── Connection states ────────────────────────────────────────────

enum class PlivoConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// ─── WebSocket message types ─────────────────────────────────────

@Serializable
data class AndroidAudioMessage(
    val type: String = "audio",
    val payload: String,  // base64 mu-law 8000 Hz
)

@Serializable
data class AndroidMediaMessage(
    val type: String = "",  // "media" or "dtmf"
    val payload: String = "",
    val digit: String = "",
)

class PlivoWebSocketBridge(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val _connectionState = MutableStateFlow(PlivoConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PlivoConnectionState> = _connectionState

    private var currentCallId: String? = null
    private var wsJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    // Registered callback for incoming audio from the caller
    private var onIncomingFrame: ((ByteArray) -> Unit)? = null

    /** Register callback for incoming mu-law audio frames from caller. */
    fun setOnIncomingFrame(callback: (ByteArray) -> Unit) {
        onIncomingFrame = callback
    }

    // ─── WebSocket lifecycle ─────────────────────────────────────

    /**
     * Connect to the Plivo bridge server for a given call.
     * The server will relay Plivo's caller audio to us via this WebSocket.
     */
    fun connect(callId: String): Job {
        disconnect()
        _connectionState.value = PlivoConnectionState.CONNECTING
        currentCallId = callId

        val wsUrl = "${Constants.PLIVO_BRIDGE_WS_URL}/android/$callId"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for streaming
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = PlivoConnectionState.CONNECTED
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = Json.decodeFromString<AndroidMediaMessage>(text)
                    when (msg.type) {
                        "media" -> {
                            // Decode base64 mu-law audio and forward to pipeline
                            val audioBytes = android.util.Base64.decode(msg.payload, android.util.Base64.DEFAULT)
                            onIncomingFrame?.invoke(audioBytes)
                        }
                        "dtmf" -> {
                            // Handle DTMF digits
                            onDTMFPressed?.invoke(msg.digit)
                        }
                    }
                } catch (e: Exception) {
                    // ignore parse errors
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = PlivoConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = PlivoConnectionState.ERROR
                // Attempt reconnect
                if (reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    scope.launch {
                        delay(1000L * reconnectAttempts)
                        connect(callId)
                    }
                }
            }
        })

        wsJob = Job().also {
            it.start()
            scope.launch {
                it.join()
            }
        }

        return wsJob!!
    }

    fun disconnect() {
        currentCallId = null
        wsJob?.cancel()
        wsJob = null
        _connectionState.value = PlivoConnectionState.DISCONNECTED
    }

    // ─── Send audio to Plivo (AI response) ───────────────────────

    /**
     * Send AI response audio (mu-law 8000 Hz, base64-encoded) to the caller
     * via the Plivo bridge server.
     */
    fun sendAudio(payloadB64: String) {
        val msg = Json.encodeToString(AndroidAudioMessage(payload = payloadB64))
        // The WebSocket is managed by OkHttp; we need to send via the client
        // For now, this is a placeholder — the actual sending will be done
        // through the WebSocket connection established in connect()
    }

    // ─── DTMF callback ───────────────────────────────────────────

    var onDTMFPressed: ((String) -> Unit)? = null
}
