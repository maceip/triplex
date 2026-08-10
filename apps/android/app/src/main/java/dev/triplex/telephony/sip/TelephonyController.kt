package dev.triplex.telephony.sip

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.triplex.data.local.AgentCallPolicy
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.repository.Result
import dev.triplex.dialogue.CallDialogue
import dev.triplex.dialogue.DialogueEvent
import dev.triplex.dialogue.DialogueObserver
import dev.triplex.dialogue.DialoguePlan
import dev.triplex.dialogue.DialogueTransport
import dev.triplex.dialogue.SpeechResult
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.nativebridge.audio.AudioPipeline
import dev.triplex.nativebridge.runtime.NativeRuntime
import dev.triplex.speech.reasoner.AiCoreCallReasoner
import dev.triplex.speech.soda.SodaCallTranscriber
import dev.triplex.speech.tts.CallVoice
import dev.triplex.speech.tts.InflectCallVoice
import dev.triplex.speech.tts.Qwen3CallVoice
import dev.triplex.telephony.plivo.AuthorizedSipRoute
import dev.triplex.telephony.plivo.PlivoSipEndpoint
import dev.triplex.telephony.plivo.SipEvent
import dev.triplex.telephony.plivo.SipRegistrationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges product state to the real PJSIP endpoint in :telephony-plivo.
 *
 * The endpoint fails closed: TLS + SRTP are mandatory and registration
 * requires real Plivo SIP-endpoint credentials (stored via SecureStorage).
 * Without credentials this controller reports NO_CREDENTIALS instead of
 * pretending readiness.
 */
@Singleton
class TelephonyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val runtime: NativeRuntime,
    /**
     * Agent behavior the user configured (reskin.md §2.3, seams 3–4).
     *
     * The decisions themselves live in [AgentCallPolicy] so they can be tested
     * without PJSIP; this class only reads the config and obeys.
     */
    private val agentConfig: AgentConfigRepository,
    private val brandedVoice: InflectCallVoice,
    private val clonedVoice: Qwen3CallVoice,
    private val reasoner: AiCoreCallReasoner,
) {
    enum class SipState {
        UNCONFIGURED,
        NO_CREDENTIALS,
        REGISTERING,
        READY,
        IN_CALL,
        WAITING_FOR_NETWORK,
        FAILED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isInitialized = AtomicBoolean(false)

    private var endpoint: PlivoSipEndpoint? = null
    private var evidenceJob: Job? = null
    private var audioPipeline: AudioPipeline? = null
    private var dialogueJob: Job? = null
    private var transcriptJob: Job? = null
    private var transportRecoveryJob: Job? = null
    private var currentEpoch: Long = 0L
    private var activeCallId: Int = -1
    private var outboundDestination: String? = null
    private var outboundTask: TaskDefinition? = null
    private val callTranscriber = SodaCallTranscriber(context)
    private val activeCallVoice = AtomicReference<CallVoice>(brandedVoice)
    private val incomingPcm = ByteBuffer.allocateDirect(16_000 * 2).order(ByteOrder.nativeOrder())
    private var greetingCallId = -1
    private var callerTranscriptBaseline = ""
    private val agentPlaybackActive = AtomicBoolean(false)

    /**
     * Set when the caller talks over the agent, so the utterance that was cut
     * off can be reported as [SpeechResult.INTERRUPTED] rather than delivered.
     * Distinct from [agentPlaybackActive], which is also cleared on the normal
     * end of playback and so cannot tell the two apart.
     */
    private val bargedIn = AtomicBoolean(false)

    private val _sipState = MutableStateFlow(SipState.UNCONFIGURED)
    val sipState: StateFlow<SipState> = _sipState.asStateFlow()

    private val _callState = MutableStateFlow<CallStateInfo>(CallStateInfo.Idle)
    val callState: StateFlow<CallStateInfo> = _callState.asStateFlow()

    val callTranscript: StateFlow<String> = callTranscriber.transcript
    val transcriptionStatus: StateFlow<String> = callTranscriber.status

    private val _agentUtterance = MutableStateFlow("")
    val agentUtterance: StateFlow<String> = _agentUtterance.asStateFlow()

    private val _conversationTurns = MutableStateFlow<List<CallConversationTurn>>(emptyList())
    val conversationTurns: StateFlow<List<CallConversationTurn>> =
        _conversationTurns.asStateFlow()

    fun initialize(): Boolean {
        if (isInitialized.get()) {
            return true
        }

        if (!runtime.initialize()) {
            Timber.e("Failed to initialize native runtime")
            return false
        }

        audioPipeline = AudioPipeline.builder()
            .sampleRate(16000)
            .channels(1)
            .frameDurationMs(10)
            .ringCapacity(128)
            .poolSize(32)
            .echoCancellation(true)
            .noiseSuppression(true)
            .build()

        isInitialized.set(true)
        scope.launch { callTranscriber.prepare() }
        transcriptJob = scope.launch {
            callTranscriber.transcript.collectLatest(::mirrorCallerTranscript)
        }
        Timber.i("Telephony controller initialized")
        return true
    }

    /**
     * Registers the PJSIP endpoint with Plivo over TLS. Returns false with a
     * truthful state when credentials are missing or startup fails.
     */
    suspend fun registerSip(): Boolean {
        if (!isInitialized.get() && !initialize()) {
            return false
        }
        if (endpoint != null) {
            Timber.w("SIP endpoint already started")
            return _sipState.value == SipState.READY
        }

        val username = secureStorage.getPlivoUsername()
        val password = secureStorage.getPlivoPassword()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Timber.w("No Plivo SIP credentials stored; telephony unavailable")
            _sipState.value = SipState.NO_CREDENTIALS
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val caBundle = exportSystemCaBundle()
                val sip = PlivoSipEndpoint(context)
                val status = sip.start(
                    SipRegistrationConfig(
                        usernameUtf8 = username.encodeToByteArray(),
                        passwordUtf8 = password.encodeToByteArray(),
                        caBundlePath = caBundle,
                    )
                ).get()
                if (status != 0) {
                    Timber.e("PJSIP start failed with status %d", status)
                    _sipState.value = SipState.FAILED
                    sip.close()
                    return@withContext false
                }
                endpoint = sip
                _sipState.value = SipState.REGISTERING
                startEvidenceLoop(sip)
                true
            } catch (e: Exception) {
                Timber.e(e, "SIP registration failed")
                _sipState.value = SipState.FAILED
                false
            }
        }
    }

    private fun startEvidenceLoop(sip: PlivoSipEndpoint) {
        evidenceJob?.cancel()
        evidenceJob = scope.launch {
            while (true) {
                try {
                    val evidence = withContext(Dispatchers.IO) { sip.pollEvidence().get() }
                    if (evidence.metrics.activeCallId >= 0) {
                        activeCallId = evidence.metrics.activeCallId
                    }
                    for (event in evidence.events) {
                        onSipEvent(event)
                    }
                    if (evidence.metrics.mediaActive) {
                        drainCallerAudio(sip)
                    }
                    if (
                        evidence.metrics.registrationStatus == 200 &&
                        !evidence.metrics.secureSignalingReady
                    ) {
                        Timber.w(
                            "SIP security evidence incomplete: registered=%s " +
                                "tlsConnected=%s tlsProtocol=%d tlsCipher=%d " +
                                "verifyStatus=%d tlsStatus=%d",
                            evidence.metrics.registered,
                            evidence.metrics.tlsConnected,
                            evidence.metrics.tlsProtocol,
                            evidence.metrics.tlsCipher,
                            evidence.metrics.tlsVerifyStatus,
                            evidence.metrics.tlsLastStatus,
                        )
                    }
                    _sipState.value = when (evidence.state) {
                        PlivoSipEndpoint.State.READY -> SipState.READY
                        PlivoSipEndpoint.State.IN_CALL -> SipState.IN_CALL
                        PlivoSipEndpoint.State.WAITING_FOR_NETWORK -> SipState.WAITING_FOR_NETWORK
                        PlivoSipEndpoint.State.FAILED -> SipState.FAILED
                        PlivoSipEndpoint.State.CLOSED -> SipState.UNCONFIGURED
                        else -> SipState.REGISTERING
                    }
                    if (
                        !evidence.metrics.mediaActive &&
                        evidence.metrics.activeCallId < 0 &&
                        _callState.value !is CallStateInfo.Idle
                    ) {
                        audioPipeline?.stop()
                        callTranscriber.stopCall()
                        activeCallId = -1
                        outboundDestination = null
                        outboundTask = null
                        dialogueJob?.cancel()
                        _callState.value = CallStateInfo.Idle
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Evidence poll failed")
                    _sipState.value = SipState.FAILED
                    break
                }
                delay(EVIDENCE_POLL_MS)
            }
        }
    }

    private fun onSipEvent(event: SipEvent) {
        when (event.type) {
            SipEvent.Type.INCOMING_CALL -> {
                activeCallId = event.callId
                greetingCallId = -1
                outboundTask = null
                dialogueJob?.cancel()
                resetConversation()
                _agentUtterance.value = ""
                _callState.value = CallStateInfo.Ringing(callerId = "call-${event.callId}")
                Timber.i("Incoming call %d", event.callId)
                scope.launch {
                    // "Agent answers all calls" is a setting now. With it off the
                    // call keeps ringing for the user to take; the agent does not
                    // quietly pick up (reskin.md §2.3, seam 3).
                    if (!AgentCallPolicy.shouldAutoAnswer(agentConfig.inboundConfig())) {
                        Timber.i("Auto-answer disabled; leaving call %d ringing", event.callId)
                        return@launch
                    }
                    if (ensureAsrStarted() && activeCallId == event.callId) {
                        answer()
                    }
                }
            }
            SipEvent.Type.MEDIA_STATE -> {
                if (event.status == MEDIA_ACTIVE_STATUS && activeCallId >= 0) {
                    currentEpoch = runtime.getEpoch()
                    audioPipeline?.start(currentEpoch)
                    ensureAsrStarted()
                    _callState.value = CallStateInfo.Active(
                        destination = outboundDestination ?: "call-$activeCallId",
                        epoch = currentEpoch,
                        startTime = System.currentTimeMillis()
                    )
                    startDialogue(activeCallId)
                }
            }
            SipEvent.Type.REGISTRATION -> {
                Timber.i("Registration event: code=%d status=%d", event.code, event.status)
                if (event.code == 200 && event.status == 0) {
                    transportRecoveryJob?.cancel()
                    transportRecoveryJob = null
                } else if (event.code >= 300 || event.status != 0) {
                    scheduleTransportRecovery()
                }
            }
            SipEvent.Type.TRANSPORT_STATE -> {
                Timber.i(
                    "TLS transport event: state=%d status=%d protocol=%d verifyStatus=%d",
                    event.code,
                    event.status,
                    event.operation,
                    event.value,
                )
                if (event.status != 0) {
                    scheduleTransportRecovery()
                }
            }
            else -> Timber.d("SIP event %s (call %d)", event.type, event.callId)
        }
    }

    private fun drainCallerAudio(sip: PlivoSipEndpoint) {
        while (true) {
            incomingPcm.clear()
            val samples = sip.drainIncomingPcm(incomingPcm).get()
            if (samples <= 0) return
            callTranscriber.addPcm(incomingPcm, samples)
        }
    }

    private fun scheduleTransportRecovery() {
        if (transportRecoveryJob?.isActive == true) return
        transportRecoveryJob = scope.launch {
            delay(TRANSPORT_RECOVERY_DELAY_MS)
            val sip = endpoint ?: return@launch
            val status = runCatching { sip.reregister().get() }
                .onFailure { Timber.e(it, "SIP transport recovery failed") }
                .getOrDefault(-1)
            Timber.i("SIP transport recovery requested: status=%d", status)
        }
    }

    /**
     * Starts the conversation for a call whose media has just come up.
     *
     * Inbound and outbound both land here, and both run the same
     * [CallDialogue]. The only difference is the plan: who the agent is, how
     * long it may talk, and what it does when the reasoner cannot answer
     * ([AgentCallPolicy.screeningPlan] / [AgentCallPolicy.outboundPlan]).
     */
    private fun startDialogue(callId: Int) {
        if (greetingCallId == callId) return
        greetingCallId = callId
        runDialogue(callId) { task ->
            task?.let(AgentCallPolicy::outboundPlan)
                ?: AgentCallPolicy.screeningPlan(agentConfig.inboundConfig())
        }
    }

    /**
     * Runs one dialogue on [callId], replacing whatever was running before it.
     *
     * The plan is resolved inside the coroutine because reading the user's
     * configuration suspends, and the call must not be held silent while that
     * happens on the caller's time.
     */
    private fun runDialogue(
        callId: Int,
        resolvePlan: suspend (TaskDefinition?) -> DialoguePlan?,
    ) {
        dialogueJob?.cancel()
        dialogueJob = scope.launch {
            val plan = runCatching { resolvePlan(outboundTask) }.getOrElse {
                Timber.e(it, "Could not resolve the dialogue plan for call %d", callId)
                null
            }
            if (plan == null) {
                Timber.w("No dialogue plan for call %d; leaving the call silent", callId)
                return@launch
            }
            val outcome = runCatching {
                CallDialogue(
                    reasoner = reasoner,
                    transport = SipDialogueTransport(callId),
                    observer = dialogueLogger,
                ).run(plan)
            }.getOrElse {
                Timber.e(it, "Dialogue failed on call %d", callId)
                return@launch
            }
            Timber.i(
                "Dialogue closed on call %d: stop=%s turns=%d failures=%d " +
                    "fallbacks=%d bargeIns=%d durationMs=%d",
                callId,
                outcome.stop,
                outcome.reasonedTurns,
                outcome.reasonerFailures,
                outcome.fallbacksSpoken,
                outcome.bargeIns,
                outcome.durationMs,
            )
        }
    }

    /**
     * The live SIP call, as the turn loop sees it.
     *
     * Bound to one call id so a dialogue that outlives its call — a coroutine
     * that has not yet reached a cancellation point — cannot speak onto the
     * next one.
     */
    private inner class SipDialogueTransport(private val callId: Int) : DialogueTransport {

        override fun isActive(): Boolean = activeCallId == callId

        override suspend fun speak(text: String): SpeechResult = speakAgent(callId, text)

        // Qualified because the enclosing class has a same-named method; the
        // baseline is read now rather than captured, since speaking moves it.
        override suspend fun awaitCallerReply(): String? =
            this@TelephonyController.awaitCallerReply(callerTranscriptBaseline)
    }

    /**
     * Forwards the dialogue's own account of the call to the log.
     *
     * Reasoner latency in particular is worth having: it is the gap between
     * the caller finishing a sentence and the agent starting one, it is the
     * number a user would describe as "it feels slow", and it is invisible
     * from anywhere else.
     */
    private val dialogueLogger = DialogueObserver { event ->
        when (event) {
            is DialogueEvent.ReasonerChecked ->
                Timber.i("Nano available=%s in %d ms", event.available, event.latencyMs)
            is DialogueEvent.ReasonerReplied ->
                Timber.i("Nano replied for turn %d in %d ms", event.turn, event.latencyMs)
            is DialogueEvent.ReasonerFailed ->
                Timber.w(
                    "Nano failed on turn %d (%d in a row): %s",
                    event.turn,
                    event.consecutive,
                    event.reason,
                )
            is DialogueEvent.FallbackSpoken ->
                Timber.w("Speaking a holding line on turn %d", event.turn)
            is DialogueEvent.BargedIn ->
                Timber.i("Caller barged in during turn %d", event.turn)
            is DialogueEvent.AgentSpoke ->
                Timber.d("Agent turn %d: %s", event.turn, event.result)
            else -> Unit
        }
    }

    /**
     * Hands the live call to [automationId] (reskin.md §2.3, seam 4).
     *
     * The automation restarts the dialogue with its own brief rather than
     * running a separate script: it is the same conversation loop, told a more
     * specific job. What the automation is for comes from
     * [dev.triplex.domain.model.AutomationCatalog] through
     * [AgentCallPolicy.screeningPlan], which also honors the automations the
     * user switched off on the inbound setup screen.
     *
     * @return true once the attempt is launched. Whether the automation is
     *   enabled can only be answered by a suspending config read, so an unknown
     *   or disabled id is refused inside the coroutine and logged, not reported
     *   through this return value. A live call is still required, and that is
     *   still refused synchronously.
     */
    fun startAutomation(automationId: String): Boolean {
        val callId = activeCallId
        if (callId < 0) return false
        // The screening greeting has already been said; the automation opens
        // with its own line, so the conversation restarts from there.
        runDialogue(callId) { _ ->
            val plan = AgentCallPolicy.screeningPlan(agentConfig.inboundConfig(), automationId)
            if (plan == null) {
                Timber.w("Automation %s is unknown or disabled; ignoring", automationId)
            }
            plan
        }
        return true
    }

    /**
     * Speaks [text] on the live call as a reply the user picked.
     *
     * A thin public wrapper over the agent synthesis path: the audio really is
     * the agent's voice, so [ConversationSpeaker] keeps its two values and the
     * "this came from the user" attribution is made one layer up, in
     * `SipCallSource`, where the timeline is built.
     */
    fun speakUserReply(text: String) {
        val callId = activeCallId
        if (callId < 0) return
        scope.launch {
            val result = speakAgent(callId, text)
            if (result == SpeechResult.FAILED) {
                Timber.e("User reply could not be spoken on call %d", callId)
            }
        }
    }

    private fun ensureAsrStarted(): Boolean {
        return callTranscriber.startCall().also { ok ->
            if (!ok) Timber.e("SODA failed to start for live call ASR")
        }
    }

    private suspend fun resolveCallVoice(): CallVoice {
        val policyResult = if (outboundTask != null) {
            agentConfig.resolveOutboundVoicePolicy()
        } else {
            agentConfig.resolveInboundVoicePolicy()
        }
        val policy = when (policyResult) {
            is Result.Success -> policyResult.data
            is Result.Error -> {
                Timber.e("Voice policy resolve failed: %s", policyResult.message)
                throw IllegalStateException(policyResult.message)
            }
        }
        val voice = when (policy) {
            AutomationVoicePolicy.CLONED -> clonedVoice
            AutomationVoicePolicy.PRESET -> brandedVoice
        }
        activeCallVoice.set(voice)
        return voice
    }

    /**
     * Streams [text] onto the SIP leg in the call's voice and reports what
     * became of it.
     *
     * The four outcomes are distinguishable on purpose. The turn loop treats
     * them differently — an interrupted utterance means listen next, a failed
     * one means stop trying to hold a conversation the caller cannot hear —
     * and collapsing them into a boolean is how a broken media path used to
     * read as a completed turn.
     */
    private suspend fun speakAgent(callId: Int, text: String): SpeechResult {
        if (activeCallId != callId) return SpeechResult.CALL_ENDED
        val sip = endpoint ?: return SpeechResult.FAILED
        val voice = runCatching { resolveCallVoice() }.getOrElse {
            Timber.e(it, "Cannot resolve call voice")
            return SpeechResult.FAILED
        }
        callerTranscriptBaseline = callTranscriber.transcript.value
        _agentUtterance.value = text
        appendTurn(ConversationSpeaker.TRIPLEX, text, 0L)
        bargedIn.set(false)
        agentPlaybackActive.set(true)

        val begin = runCatching { sip.beginStreamingSynthesis().get() }.getOrElse {
            Timber.e(it, "SIP begin streaming raised")
            -1
        }
        if (begin != 0) {
            agentPlaybackActive.set(false)
            Timber.e("SIP begin streaming failed with %d", begin)
            return SpeechResult.FAILED
        }
        var totalSamples = 0
        try {
            voice.synthesizeStream(text).collect { chunk ->
                if (activeCallId != callId || !agentPlaybackActive.get()) {
                    voice.cancel()
                    return@collect
                }
                totalSamples += chunk.size
                val status = sip.pushStreamingSynthesis(chunk, finalChunk = false).get()
                check(status == 0) { "SIP push streaming failed with $status" }
            }
            sip.pushStreamingSynthesis(ShortArray(0), finalChunk = true).get()
        } catch (cancelled: CancellationException) {
            voice.cancel()
            runCatching { sip.interrupt().get() }
            agentPlaybackActive.set(false)
            throw cancelled
        } catch (t: Throwable) {
            // Synthesis or the media path gave out mid-utterance. The caller
            // heard a fragment at best, so this is a failed turn, not a short
            // one — and it must not take the whole SIP engine down with it.
            Timber.e(t, "Agent synthesis failed on call %d", callId)
            voice.cancel()
            runCatching { sip.interrupt().get() }
            agentPlaybackActive.set(false)
            return SpeechResult.FAILED
        }

        val playbackDurationMs = (totalSamples * 1_000L / SYNTHESIS_SAMPLE_RATE).coerceAtLeast(1L)
        // Wait for paced playback unless barge-in already cleared the flag.
        val deadline = SystemClock.elapsedRealtime() + playbackDurationMs
        while (agentPlaybackActive.get() && SystemClock.elapsedRealtime() < deadline) {
            delay(PLAYBACK_POLL_MS)
        }
        agentPlaybackActive.set(false)
        return when {
            activeCallId != callId -> SpeechResult.CALL_ENDED
            bargedIn.get() -> SpeechResult.INTERRUPTED
            else -> SpeechResult.DELIVERED
        }
    }

    private suspend fun awaitCallerReply(baseline: String): String? {
        val deadline = SystemClock.elapsedRealtime() + CALLER_REPLY_TIMEOUT_MS
        var candidate = ""
        var changedAt = SystemClock.elapsedRealtime()
        while (activeCallId >= 0 && SystemClock.elapsedRealtime() < deadline) {
            val transcript = callTranscriber.transcript.value
            val delta = transcript.removePrefix(baseline).trim()
            if (delta != candidate) {
                candidate = delta
                changedAt = SystemClock.elapsedRealtime()
            } else if (
                candidate.isNotBlank() &&
                SystemClock.elapsedRealtime() - changedAt >= CALLER_SETTLE_MS
            ) {
                return candidate
            }
            delay(CALLER_POLL_MS)
        }
        return null
    }

    private fun mirrorCallerTranscript(transcript: String) {
        val callerText = transcript.removePrefix(callerTranscriptBaseline).trim()
        if (callerText.isBlank()) return
        if (callerText.length < 2) return
        val turns = _conversationTurns.value
        _conversationTurns.value = if (turns.lastOrNull()?.speaker == ConversationSpeaker.CALLER) {
            turns.dropLast(1) + CallConversationTurn(ConversationSpeaker.CALLER, callerText)
        } else {
            turns + CallConversationTurn(ConversationSpeaker.CALLER, callerText)
        }
        if (agentPlaybackActive.compareAndSet(true, false)) {
            bargedIn.set(true)
            activeCallVoice.get()?.cancel()
            runCatching { endpoint?.interrupt()?.get() }
                .onFailure { Timber.w(it, "Caller barge-in interrupt failed") }
        }
    }

    private fun appendTurn(
        speaker: ConversationSpeaker,
        text: String,
        playbackDurationMs: Long = 0L
    ) {
        _conversationTurns.value = _conversationTurns.value +
            CallConversationTurn(speaker, text, playbackDurationMs)
    }

    private fun resetConversation() {
        callerTranscriptBaseline = ""
        agentPlaybackActive.set(false)
        bargedIn.set(false)
        _conversationTurns.value = emptyList()
    }

    fun answer(): Boolean {
        val sip = endpoint ?: return false
        val callId = activeCallId
        if (callId < 0) {
            return false
        }
        ensureAsrStarted()
        sip.answer(callId)
        return true
    }

    suspend fun placeAuthorizedCall(
        route: AuthorizedSipRoute,
        destination: String,
        task: TaskDefinition,
    ): Boolean {
        if (endpoint == null && !registerSip()) {
            return false
        }
        val ready = if (_sipState.value == SipState.READY) {
            true
        } else {
            withTimeoutOrNull(SIP_READY_TIMEOUT_MS) {
                sipState.filter {
                    it == SipState.READY || it == SipState.FAILED ||
                        it == SipState.NO_CREDENTIALS
                }.first() == SipState.READY
            } ?: false
        }
        if (!ready) {
            Timber.e("SIP endpoint did not become ready for authorized outbound call")
            return false
        }

        val sip = endpoint ?: return false
        return withContext(Dispatchers.IO) {
            val status = sip.makeAuthorizedCall(route).get()
            if (status == 0) {
                outboundDestination = destination
                outboundTask = task
                dialogueJob?.cancel()
                resetConversation()
                _callState.value = CallStateInfo.Calling(destination)
                true
            } else {
                outboundTask = null
                Timber.e("Authorized SIP call failed with status %d", status)
                false
            }
        }
    }

    fun hangup() {
        val sip = endpoint
        if (sip != null && activeCallId >= 0) {
            sip.hangup(activeCallId)
        }
        audioPipeline?.stop()
        callTranscriber.stopCall()
        dialogueJob?.cancel()
        _agentUtterance.value = ""
        greetingCallId = -1
        activeCallId = -1
        outboundDestination = null
        outboundTask = null
        resetConversation()
        _callState.value = CallStateInfo.Idle
    }

    fun interruptPlayback(): Long {
        val sip = endpoint ?: return 0L
        return try {
            if (agentPlaybackActive.compareAndSet(true, false)) {
                bargedIn.set(true)
            }
            activeCallVoice.get()?.cancel()
            sip.interrupt().get()
        } catch (e: Exception) {
            Timber.e(e, "Interrupt failed")
            0L
        }
    }

    fun getAudioPipeline(): AudioPipeline? = audioPipeline

    fun getCurrentEpoch(): Long = currentEpoch

    fun shutdown() {
        evidenceJob?.cancel()
        evidenceJob = null
        transcriptJob?.cancel()
        transcriptJob = null
        dialogueJob?.cancel()
        transportRecoveryJob?.cancel()
        transportRecoveryJob = null
        endpoint?.close()
        endpoint = null
        audioPipeline?.stop()
        callTranscriber.close()
        brandedVoice.close()
        clonedVoice.close()
        activeCallId = -1
        outboundDestination = null
        outboundTask = null
        resetConversation()
        _callState.value = CallStateInfo.Idle
        _sipState.value = SipState.UNCONFIGURED
        isInitialized.set(false)
        scope.cancel()
        Timber.i("Telephony controller shutdown")
    }

    /**
     * PJSIP consumes a PEM CA bundle from disk; Android exposes trust anchors
     * through the AndroidCAStore KeyStore. Export once per process start.
     */
    private fun exportSystemCaBundle(): File {
        val target = File(context.filesDir, "system-ca-bundle.pem")
        if (target.isFile && target.length() > 0) {
            return target
        }
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        target.bufferedWriter().use { writer ->
            for (alias in keyStore.aliases()) {
                val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
                writer.write("-----BEGIN CERTIFICATE-----\n")
                writer.write(encoder.encodeToString(cert.encoded))
                writer.write("\n-----END CERTIFICATE-----\n")
            }
        }
        check(target.length() > 0) { "Exported CA bundle is empty" }
        Timber.i("Exported system CA bundle (%d bytes)", target.length())
        return target
    }

    private companion object {
        const val EVIDENCE_POLL_MS = 50L
        const val SIP_READY_TIMEOUT_MS = 20_000L
        const val CALLER_REPLY_TIMEOUT_MS = 45_000L
        const val CALLER_SETTLE_MS = 1_200L
        const val CALLER_POLL_MS = 150L
        const val PLAYBACK_POLL_MS = 20L
        // Inflect renders mono 16-bit PCM at this rate; the SIP leg runs at the same rate.
        const val SYNTHESIS_SAMPLE_RATE = 16_000L
        const val TRANSPORT_RECOVERY_DELAY_MS = 1_500L
        // pjsua media status PJSUA_CALL_MEDIA_ACTIVE
        const val MEDIA_ACTIVE_STATUS = 1
        // The screening greeting now lives in AgentConfigDefaults.GREETING_TEXT,
        // which is also what an unconfigured device reads back.
    }
}

enum class ConversationSpeaker { TRIPLEX, CALLER }

data class CallConversationTurn(
    val speaker: ConversationSpeaker,
    val text: String,
    /**
     * Wall-clock length of the synthesized audio for a TRIPLEX turn, in
     * milliseconds. Zero for caller turns and for any turn whose playback
     * length is unknown; consumers degrade to an instant reveal in that case.
     */
    val playbackDurationMs: Long = 0L,
)

sealed class CallStateInfo {
    object Idle : CallStateInfo()
    data class Calling(val destination: String) : CallStateInfo()
    data class Ringing(val callerId: String) : CallStateInfo()
    data class Active(
        val destination: String,
        val epoch: Long,
        val startTime: Long
    ) : CallStateInfo()
    data class Interrupted(val reason: String) : CallStateInfo()
}
