package com.triplex.dialer.triplex

import android.content.Context
import android.util.Log
import com.triplex.dialer.model.CallData
import com.triplex.dialer.model.CallDirection
import com.triplex.dialer.model.ChimeConnectionState
import com.triplex.dialer.model.EchoCancellationState
import com.triplex.dialer.model.TriplexCallCapabilities
import com.triplex.dialer.model.TriplexCallPhase
import com.triplex.dialer.model.TriplexCallSession
import com.triplex.dialer.model.VoiceCloneProfile
import com.triplex.dialer.model.YankRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Central orchestrator for Triplex voice-agent lifecycle on Android.
 *
 * Owns Plivo bridge, audio pipeline, echo cancellation, interruption,
 * voice synthesis, and call-session state.  Exposes a thin API consumed
 * by TelecomVoipService, DialerViewModel, and the in-call UI.
 */
class TriplexClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _capabilities = MutableStateFlow(TriplexCallCapabilities())
    val capabilities: StateFlow<TriplexCallCapabilities> = _capabilities

    private val _sessionState = MutableStateFlow<TriplexCallSession?>(null)
    val sessionState: StateFlow<TriplexCallSession?> = _sessionState

    var currentCall: CallData? = null
        private set

    // Sub-engine instances created by initialize()
    private var plivoBridge: PlivoWebSocketBridge? = null
    private var audioPipeline: AudioPipeline? = null
    private var echoCanceller: EchoCanceller? = null
    private var interruptionHandler: InterruptionHandler? = null
    private var voiceSynthesis: VoiceSynthesis? = null

    /** Initialize all Triplex sub-engines. */
    fun initialize(context: Context) {
        Log.d("TriplexClient", "initialize: creating sub-engines")
        plivoBridge = PlivoWebSocketBridge(context, scope)
        audioPipeline = AudioPipeline(context, scope)
        echoCanceller = EchoCanceller()
        interruptionHandler = InterruptionHandler(scope)
        voiceSynthesis = VoiceSynthesis(scope)
        Log.d("TriplexClient", "initialize: sub-engines created, launching capability collectors")

        plivoBridge = PlivoWebSocketBridge(context, scope)
        audioPipeline = AudioPipeline(context, scope)
        echoCanceller = EchoCanceller()
        interruptionHandler = InterruptionHandler(scope)
        voiceSynthesis = VoiceSynthesis(scope)

        // Wire capability state flows into combined capabilities
        scope.launch {
            plivoBridge.connectionState.collect { plivo ->
                _capabilities.value = _capabilities.value.copy(chimeConnection = when (plivo) {
                    PlivoConnectionState.DISCONNECTED -> ChimeConnectionState.DISCONNECTED
                    PlivoConnectionState.CONNECTING -> ChimeConnectionState.CONNECTING
                    PlivoConnectionState.CONNECTED -> ChimeConnectionState.CONNECTED
                    PlivoConnectionState.ERROR -> ChimeConnectionState.DISCONNECTED
                })
            }
        }
        scope.launch {
            echoCanceller.state.collect { echo ->
                _capabilities.value = _capabilities.value.copy(echoCancellation = echo)
            }
        }
        scope.launch {
            interruptionHandler.state.collect { intr ->
                _capabilities.value = _capabilities.value.copy(interruption = intr)
            }
        }
    }

    // ─── Yank lifecycle ───────────────────────────────────────────

    /**
     * AI hit a roadblock — needs user input.
     * Sets pendingYank on the session, pauses AI speech, notifies UI.
     */
    fun requestUserInput(yankRequest: YankRequest) {
        val session = _sessionState.value ?: return
        _sessionState.value = session.copy(
            phase = TriplexCallPhase.AWAITING_USER_INPUT,
            pendingYank = yankRequest,
        )
        // Pause AI speech — stop TTS playback
        voiceSynthesis.pause()
    }

    /**
     * User provided the requested info — resume AI.
     * The AI will speak the user's answer to the business/caller.
     */
    fun provideUserInput(answer: String) {
        val session = _sessionState.value ?: return
        // Clear the yank, resume AI speech
        _sessionState.value = session.copy(
            phase = TriplexCallPhase.SPEAKING,
            pendingYank = null,
        )
        // Resume TTS with the answer
        voiceSynthesis.resume(answer)
    }

    /** User dismissed the yank dialog — resume AI without providing info. */
    fun cancelUserInput() {
        val session = _sessionState.value ?: return
        _sessionState.value = session.copy(
            phase = TriplexCallPhase.SPEAKING,
            pendingYank = null,
        )
        voiceSynthesis.resume(null)
    }

    // ─── DTMF lifecycle ──────────────────────────────────────────

    /** Queue DTMF tones to send during the call. */
    fun sendDTMF(digits: String, description: String = "") {
        val session = _sessionState.value ?: return
        val dtmf = DTMFSequence(digits = digits, description = description)
        _sessionState.value = session.copy(pendingDTMF = dtmf)

        // Generate and inject into audio pipeline
        val tone = DTMFGenerator.generateSequence(digits)
        audioPipeline.injectDTMF(tone)
    }

    /** Clear pending DTMF. */
    fun clearDTMF() {
        val session = _sessionState.value ?: return
        _sessionState.value = session.copy(pendingDTMF = null)
    }

    // ─── Call lifecycle ──────────────────────────────────────────

    /** Handle an incoming call — bridge from TelecomVoipService. */
    fun handleIncomingCall(call: CallData) {
        currentCall = call
        scope.launch {
            // Initialize session
            _sessionState.value = TriplexCallSession(
                direction = CallDirection.INCOMING,
                phase = TriplexCallPhase.SCREENING,
                counterpartyName = call.displayLabel(),
            )

            // 1. Initialize audio pipeline
            audioPipeline.start()

            // 2. Connect to Plivo bridge via call ID
            plivoBridge.connect(call.id)

            // 3. Start echo cancellation

            // 4. Select voice synthesis engine
            voiceSynthesis.selectEngine(null) // default Kokoro

            // 5. Signal ready
            _capabilities.value = _capabilities.value.copy(
                chimeConnection = ChimeConnectionState.CONNECTED,
                echoCancellation = EchoCancellationState.ACTIVE,
            )
        }
    }

    /** Initiate an outgoing call via Plivo telephony. */
    fun placeCall(dialedNumber: String, cloneProfile: VoiceCloneProfile? = null) {
        scope.launch {
            val call = CallData(
                dialedNumber = dialedNumber,
                isIncoming = false,
                isTriplexAgent = cloneProfile != null,
            )

            // Initialize session
            _sessionState.value = TriplexCallSession(
                direction = CallDirection.OUTGOING,
                phase = TriplexCallPhase.DIALING,
                counterpartyName = dialedNumber,
            )

            // 1. Connect audio pipeline
            audioPipeline.start()
            plivoBridge.connect(call.id)

            // 3. Select voice
            voiceSynthesis.selectEngine(cloneProfile)

            // 4. Session transitions to SPEAKING
            _sessionState.value = _sessionState.value?.copy(phase = TriplexCallPhase.SPEAKING)

            currentCall = call
        }
    }

    /** End the current call — teardown all engines. */
    fun endCall(call: CallData) {
        scope.launch {
            plivoBridge.disconnect()
            audioPipeline.stop()
            echoCanceller.reset()
            voiceSynthesis.reset()
            _sessionState.value = null
            currentCall = null
        }
    }

    /** Mute/unmute the local microphone. */
    fun setMuted(call: CallData, muted: Boolean) {
        // Forward to Chime SDK audio client
    }

    /** Put a call on local hold. */
    fun setHeld(call: CallData, isLocalHold: Boolean) {
        // Forward to Chime SDK — mute egress, keep ingress
    }

    /** Transfer an incoming call to the user. */
    fun transferToUser(call: CallData) {
        // 1. Stop AI speech
        voiceSynthesis.reset()
        // 2. Bridge audio to user's earpiece
        // 3. End session tracking
        _sessionState.value = null
    }
}
