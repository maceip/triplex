package dev.triplex.telephony.plivo

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class SipRegistrationConfig(
    val usernameUtf8: ByteArray,
    val passwordUtf8: ByteArray,
    val domainUtf8: ByteArray = "phone.plivo.com".encodeToByteArray(),
    val realmUtf8: ByteArray? = null,
    val caBundlePath: File,
    val tlsPort: Int = 5061,
    val registrationTimeoutSeconds: Int = 300,
) {
    init {
        require(usernameUtf8.isNotEmpty())
        require(passwordUtf8.isNotEmpty())
        require(domainUtf8.isNotEmpty())
        require(tlsPort in 1..65_535)
        require(registrationTimeoutSeconds in 30..3_600)
        require(caBundlePath.isFile && caBundlePath.canRead()) {
            "A readable CA bundle is required; insecure TLS is not supported"
        }
    }

    internal fun destroyPassword() = passwordUtf8.fill(0)
}

data class AuthorizedSipRoute(
    val taskId: String,
    val sipUri: String,
    val headerName: String,
    val headerValue: String,
    val expiresAtElapsedRealtimeNs: Long,
) {
    init {
        require(taskId.isNotBlank())
        require(sipUri.length in 5..640)
        require(
            (sipUri.startsWith("sip:") || sipUri.startsWith("sips:")) &&
                "phone.plivo.com" in sipUri,
        ) {
            "Outbound route grants must target phone.plivo.com over sip or sips"
        }
        require('\r' !in sipUri && '\n' !in sipUri)
        require(headerName == OUTBOUND_GRANT_HEADER)
        require(headerValue.length in 16..4_095)
        require('\r' !in headerValue && '\n' !in headerValue)
    }

    fun requireUsable() {
        require(SystemClock.elapsedRealtimeNanos() < expiresAtElapsedRealtimeNs) {
            "Gateway route grant has expired"
        }
    }

    private companion object {
        const val OUTBOUND_GRANT_HEADER = "X-PH-TriplexGrant"
    }
}

data class EndpointEvidence(
    val state: PlivoSipEndpoint.State,
    val events: List<SipEvent>,
    val metrics: DirectTransportMetrics,
)

class PlivoSipEndpoint(context: Context) : AutoCloseable {
    enum class State {
        STOPPED,
        STARTING,
        REGISTERING,
        READY,
        IN_CALL,
        WAITING_FOR_NETWORK,
        FAILED,
        CLOSED,
    }

    private val appContext = context.applicationContext
    @Volatile private var controlThread: Thread? = null
    private val control: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "triplex-sip-control").also { controlThread = it }
        },
    )
    private val eventBuffer = ByteBuffer.allocateDirect(
        NativePjsip.EVENT_BYTES * NativePjsip.MAX_EVENT_BATCH,
    ).order(ByteOrder.nativeOrder())
    private val metricsBuffer = ByteBuffer.allocateDirect(NativePjsip.METRICS_BYTES)
        .order(ByteOrder.nativeOrder())
    private val networkHandoffRecovery = PjsipNetworkHandoffRecovery(
        NativeNetworkChangeDriver(NativePjsip::nativeHandleNetworkChange),
    )

    @Volatile private var state = State.STOPPED
    private var nativeHandle = 0L
    private var networkMonitor: NetworkHandoffMonitor? = null

    fun state(): State = state

    fun start(config: SipRegistrationConfig): CompletableFuture<Int> = submit {
        check(state == State.STOPPED) { "Endpoint cannot start from $state" }
        state = State.STARTING
        try {
            nativeHandle = NativePjsip.nativeCreate(
                usernameUtf8 = config.usernameUtf8,
                passwordUtf8 = config.passwordUtf8,
                domainUtf8 = config.domainUtf8,
                realmUtf8 = config.realmUtf8,
                caBundlePathUtf8 = config.caBundlePath.absolutePath.encodeToByteArray(),
                tlsPort = config.tlsPort,
                registrationTimeoutSeconds = config.registrationTimeoutSeconds,
            )
        } finally {
            config.destroyPassword()
        }
        if (nativeHandle == 0L) {
            state = State.FAILED
            error("PJSIP initialization failed")
        }

        val status = NativePjsip.nativeStart(nativeHandle)
        if (status != 0) {
            state = State.FAILED
            NativePjsip.nativeDestroy(nativeHandle)
            nativeHandle = 0
            return@submit status
        }

        networkMonitor = NetworkHandoffMonitor(
            context = appContext,
            controlExecutor = control,
            onNetworkUnavailable = {
                if (state != State.CLOSED) state = State.WAITING_FOR_NETWORK
            },
            onValidatedHandoff = {
                if (nativeHandle != 0L) {
                    val handoffStatus = networkHandoffRecovery.recover(nativeHandle)
                    state = if (handoffStatus == 0) State.REGISTERING else State.FAILED
                }
            },
        ).also { it.start() }
        state = State.REGISTERING
        status
    }

    fun answer(callId: Int): CompletableFuture<Int> = submitNative {
        NativePjsip.nativeAnswer(it, callId)
    }

    fun makeAuthorizedCall(route: AuthorizedSipRoute): CompletableFuture<Int> = submitNative {
        route.requireUsable()
        NativePjsip.nativeMakeCall(it, route.sipUri, route.headerName, route.headerValue)
    }

    fun sendDtmf(callId: Int, digits: String): CompletableFuture<Int> = submitNative {
        NativePjsip.nativeSendDtmf(it, callId, digits)
    }

    fun interrupt(): CompletableFuture<Long> = submitNative {
        NativePjsip.nativeInterrupt(it)
    }

    fun hangup(callId: Int, sipStatus: Int = 0): CompletableFuture<Int> = submitNative {
        NativePjsip.nativeHangup(it, callId, sipStatus)
    }

    fun reregister(): CompletableFuture<Int> = submitNative {
        state = State.REGISTERING
        NativePjsip.nativeReregister(it)
    }

    /** Bounded no-AI proof: injects canonical PCM directly into the SIP media port. */
    fun startProbeTone(
        frequencyHz: Int = 1_000,
        durationMs: Int = 1_000,
        amplitude: Int = 8_000,
    ): CompletableFuture<Int> = submitNative {
        NativePjsip.nativeStartProbeTone(it, frequencyHz, durationMs, amplitude)
    }

    fun startSynthesis(samples: ShortArray): CompletableFuture<Int> = submitNative {
        require(samples.isNotEmpty()) { "Synthesis PCM cannot be empty" }
        NativePjsip.nativeStartSynthesis(it, samples)
    }

    /** Opens a paced streaming synthesis generation. Interrupt cancels it. */
    fun beginStreamingSynthesis(): CompletableFuture<Int> = submitNative {
        NativePjsip.nativeBeginStreamingSynthesis(it)
    }

    /**
     * Appends 16 kHz mono PCM to the active streaming generation.
     * Pass [finalChunk]=true on the last push (samples may be empty).
     */
    fun pushStreamingSynthesis(
        samples: ShortArray,
        finalChunk: Boolean,
    ): CompletableFuture<Int> = submitNative {
        NativePjsip.nativePushStreamingSynthesis(it, samples, finalChunk)
    }

    /** Non-RT polling surface. No native callback enters Kotlin. */
    fun pollEvidence(): CompletableFuture<EndpointEvidence> = submitNative { handle ->
        eventBuffer.clear()
        val count = NativePjsip.nativeDrainEvents(handle, eventBuffer)
        val events = SipEventDecoder.decode(eventBuffer, count)
        metricsBuffer.clear()
        check(NativePjsip.nativeMetricsSnapshot(handle, metricsBuffer)) {
            "Native metrics snapshot failed"
        }
        val metrics = TransportEvidenceDecoder.decode(metricsBuffer)
        updateState(metrics)
        EndpointEvidence(state, events, metrics)
    }

    /** Drains far-end RTP PCM into a direct native-order buffer without an RT callback. */
    fun drainIncomingPcm(output: ByteBuffer): CompletableFuture<Int> = submitNative { handle ->
        require(output.isDirect) { "Incoming PCM output must be a direct buffer" }
        output.clear()
        NativePjsip.nativeDrainIncomingPcm(handle, output)
    }

    private fun updateState(metrics: DirectTransportMetrics) {
        if (metrics.mediaActive && !metrics.secureMediaReady) {
            NativePjsip.nativeHangup(nativeHandle, metrics.activeCallId, 488)
            state = State.FAILED
        } else if (metrics.mediaActive) {
            state = State.IN_CALL
        } else if (metrics.secureSignalingReady) {
            state = State.READY
        } else if (state !in setOf(State.WAITING_FOR_NETWORK, State.FAILED)) {
            state = State.REGISTERING
        }
    }

    private fun <T> submitNative(block: (Long) -> T): CompletableFuture<T> = submit {
        check(nativeHandle != 0L) { "Endpoint is not started" }
        block(nativeHandle)
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ block() }, control)

    override fun close() {
        val closeAction = {
            if (state != State.CLOSED) {
                networkMonitor?.close()
                networkMonitor = null
                if (nativeHandle != 0L) {
                    NativePjsip.nativeDestroy(nativeHandle)
                    nativeHandle = 0L
                }
                state = State.CLOSED
            }
        }
        if (Thread.currentThread() === controlThread) {
            closeAction()
        } else {
            control.submit { closeAction() }.get(5, TimeUnit.SECONDS)
        }
        control.shutdown()
    }
}
