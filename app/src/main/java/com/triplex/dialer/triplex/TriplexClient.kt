package com.triplex.dialer.triplex

import android.content.Context
import com.triplex.dialer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level Triplex voice agent client for Android.
 *
 * Wires all four capabilities into a single lifecycle:
 * 1. Echo Cancellation — adaptive NLMS filter
 * 2. Chime/KVS Media — AWS Chime SDK meeting bridge
 * 3. Interruption Teardown — sub-50 ms barge-in
 * 4. Voice Synthesis — Kokoro or Qwen3-TTS clone
 *
 * Call lifecycle:
 *   incoming/outgoing → initialize → connect Chime → audio pipeline →
 *   VAD/ASR → LLM → TTS → playback → interruption → teardown → disconnect
 */
class TriplexClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentCall: CallData? = null

    /** Sub-engine references. */
    lateinit var chimeBridge: ChimeSdkBridge
        private set
    lateinit var audioPipeline: AudioPipeline
        private set
    lateinit var echoCanceller: EchoCanceller
        private set
    lateinit var interruptionHandler: InterruptionHandler
        private set
    lateinit var voiceSynthesis: VoiceSynthesis
        private set

    /** Combined capabilities state for the in-call UX. */
    private val _capabilities = MutableStateFlow(TriplexCallCapabilities())
    val capabilities: StateFlow<TriplexCallCapabilities> = _capabilities

    /** Initialize all Triplex sub-engines. */
    fun initialize(context: Context) {
        chimeBridge = ChimeSdkBridge(scope)
        audioPipeline = AudioPipeline(scope)
        echoCanceller = EchoCanceller()
        interruptionHandler = InterruptionHandler(scope)
        voiceSynthesis = VoiceSynthesis(scope)

        // Wire capability state flows into combined capabilities
        scope.launch {
            chimeBridge.connectionState.collect { chime ->
                _capabilities.value = _capabilities.value.copy(chimeConnection = chime)
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

    /** Handle an incoming call — bridge from TelecomVoipService. */
    fun handleIncomingCall(call: CallData) {
        currentCall = call
        scope.launch {
            // 1. Initialize audio pipeline
            audioPipeline.start()

            // 2. Connect to Chime meeting
            chimeBridge.connect("meeting-${call.id}", "agent-${call.id}")

            // 3. Start echo cancellation
            // (runs continuously in audio pipeline)

            // 4. Select voice synthesis engine
            voiceSynthesis.selectEngine(null) // default Kokoro

            // 5. Signal ready
            _capabilities.value = _capabilities.value.copy(
                chimeConnection = ChimeConnectionState.CONNECTED,
                echoCancellation = EchoCancellationState.ACTIVE,
            )
        }
    }

    /** Initiate an outgoing call via Chime SIP media app. */
    fun placeCall(dialedNumber: String, cloneProfile: VoiceCloneProfile? = null) {
        scope.launch {
            val call = CallData(
                dialedNumber = dialedNumber,
                isIncoming = false,
                isTriplexAgent = cloneProfile != null,
            )

            // 1. Create Chime meeting via SIP media app
            val meeting = ChimeSdkBridge.createMeetingViaSipMediaApp(
                callerNumber = dialedNumber,
                meetingId = "call-${call.id}",
            )

            // 2. Connect audio pipeline
            audioPipeline.start()
            chimeBridge.connect(meeting.getOrThrow()["meetingId"] ?: call.id, "agent-${call.id}")

            // 3. Select voice
            voiceSynthesis.selectEngine(cloneProfile)

            currentCall = call
        }
    }

    /** End the current call — teardown all engines. */
    fun endCall(call: CallData) {
        scope.launch {
            chimeBridge.disconnect()
            audioPipeline.stop()
            echoCanceller.reset()
            voiceSynthesis.reset()
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
}
