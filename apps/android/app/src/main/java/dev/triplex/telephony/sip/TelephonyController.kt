package dev.triplex.telephony.sip

import android.content.Context
import dev.triplex.data.local.SecureStorage
import dev.triplex.nativebridge.audio.AudioPipeline
import dev.triplex.nativebridge.runtime.NativeRuntime
import dev.triplex.telephony.plivo.PlivoSipEndpoint
import dev.triplex.telephony.plivo.SipEvent
import dev.triplex.telephony.plivo.SipRegistrationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
    private val context: Context,
    private val secureStorage: SecureStorage,
    private val runtime: NativeRuntime
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
    private var currentEpoch: Long = 0L
    private var activeCallId: Int = -1

    private val _sipState = MutableStateFlow(SipState.UNCONFIGURED)
    val sipState: StateFlow<SipState> = _sipState.asStateFlow()

    private val _callState = MutableStateFlow<CallStateInfo>(CallStateInfo.Idle)
    val callState: StateFlow<CallStateInfo> = _callState.asStateFlow()

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
                    for (event in evidence.events) {
                        onSipEvent(event)
                    }
                    _sipState.value = when (evidence.state) {
                        PlivoSipEndpoint.State.READY -> SipState.READY
                        PlivoSipEndpoint.State.IN_CALL -> SipState.IN_CALL
                        PlivoSipEndpoint.State.WAITING_FOR_NETWORK -> SipState.WAITING_FOR_NETWORK
                        PlivoSipEndpoint.State.FAILED -> SipState.FAILED
                        PlivoSipEndpoint.State.CLOSED -> SipState.UNCONFIGURED
                        else -> SipState.REGISTERING
                    }
                    if (!evidence.metrics.mediaActive && _callState.value !is CallStateInfo.Idle) {
                        audioPipeline?.stop()
                        activeCallId = -1
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
                _callState.value = CallStateInfo.Ringing(callerId = "call-${event.callId}")
                Timber.i("Incoming call %d", event.callId)
            }
            SipEvent.Type.MEDIA_STATE -> {
                if (event.status == MEDIA_ACTIVE_STATUS && activeCallId >= 0) {
                    currentEpoch = runtime.getEpoch()
                    audioPipeline?.start(currentEpoch)
                    _callState.value = CallStateInfo.Active(
                        destination = "call-$activeCallId",
                        epoch = currentEpoch,
                        startTime = System.currentTimeMillis()
                    )
                }
            }
            SipEvent.Type.REGISTRATION -> {
                Timber.i("Registration event: code=%d status=%d", event.code, event.status)
            }
            else -> Timber.d("SIP event %s (call %d)", event.type, event.callId)
        }
    }

    fun answer(): Boolean {
        val sip = endpoint ?: return false
        val callId = activeCallId
        if (callId < 0) {
            return false
        }
        sip.answer(callId)
        return true
    }

    fun hangup() {
        val sip = endpoint
        if (sip != null && activeCallId >= 0) {
            sip.hangup(activeCallId)
        }
        audioPipeline?.stop()
        activeCallId = -1
        _callState.value = CallStateInfo.Idle
    }

    fun interruptPlayback(): Long {
        val sip = endpoint ?: return 0L
        return try {
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
        endpoint?.close()
        endpoint = null
        audioPipeline?.stop()
        activeCallId = -1
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
        const val EVIDENCE_POLL_MS = 500L
        // pjsua media status PJSUA_CALL_MEDIA_ACTIVE
        const val MEDIA_ACTIVE_STATUS = 1
    }
}

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
