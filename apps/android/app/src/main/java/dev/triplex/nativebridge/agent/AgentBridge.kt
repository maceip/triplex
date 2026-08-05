package dev.triplex.nativebridge.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentState {
    STOPPED,
    LISTENING,
    ENDPOINTING,
    COMMITTED,
    SPEAKING,
    YIELDING,
    ENDED;

    companion object {
        fun fromWire(value: Int): AgentState = entries.getOrElse(value) { STOPPED }
    }
}

data class AgentStatus(
    val state: AgentState = AgentState.STOPPED,
    val epoch: Long = 0,
    val vadOnsets: Long = 0,
    val turnsCommitted: Long = 0,
    val utterancesCompleted: Long = 0,
    val bargeIns: Long = 0,
    val egressedSamples: Long = 0,
    val voicedSamples: Long = 0
)

/**
 * Kotlin face of the baseline agent session running inside the Rust cdylib
 * (VAD → turn FSM → reflex reasoner → tone TTS → paced egress, with real
 * barge-in). The session owns the media rings; it runs only while no call
 * is active.
 */
@Singleton
class AgentBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var pollJob: Job? = null

    private val statusBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(STATUS_BYTES).order(ByteOrder.nativeOrder())

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    fun start(): Boolean {
        if (running.get()) {
            return true
        }
        if (!nativeAgentInitialize()) {
            Timber.e("Baseline agent session failed to start")
            return false
        }
        running.set(true)
        pollJob = scope.launch {
            var ticks = 0
            while (running.get()) {
                pollStatus(forceLog = ticks % 10 == 0)
                ticks++
                delay(STATUS_POLL_MS)
            }
        }
        Timber.i("Baseline agent session running")
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }
        pollJob?.cancel()
        pollJob = null
        nativeAgentShutdown()
        _status.value = AgentStatus()
        Timber.i("Baseline agent session stopped")
    }

    fun interrupt(reason: String) {
        nativeInterrupt(_status.value.epoch, reason)
    }

    /**
     * Loopback demo: injects one synthetic caller utterance (350 ms square
     * wave + trailing silence) into the capture ring, paced in real time.
     * Only valid while no call is active — the injection path shares the
     * single-producer capture ring with the call media port.
     */
    fun runDemoTurn(bargeIn: Boolean = false) {
        scope.launch {
            if (!running.get()) {
                Timber.w("Demo turn ignored: agent not running")
                return@launch
            }
            Timber.i("Demo turn: injecting caller speech (bargeIn=%b)", bargeIn)
            injectFrames(amplitudeSquare(3000), count = 35)
            injectFrames(silence(), count = 40)
            if (bargeIn) {
                // Wait until the agent is audibly speaking, then talk over it.
                val started = System.currentTimeMillis()
                while (_status.value.state != AgentState.SPEAKING &&
                    System.currentTimeMillis() - started < 5_000
                ) {
                    delay(10)
                }
                if (_status.value.state == AgentState.SPEAKING) {
                    Timber.i("Demo barge-in: speaking over the agent")
                    injectFrames(amplitudeSquare(3000), count = 30)
                    injectFrames(silence(), count = 40)
                }
            }
        }
    }

    private suspend fun injectFrames(frame: ShortArray, count: Int) {
        var failed = 0
        repeat(count) {
            if (!nativeInjectCapture(frame, System.nanoTime(), 0)) {
                failed++
            }
            delay(FRAME_MS)
        }
        if (failed > 0) {
            Timber.w("Capture injection: %d/%d frames rejected", failed, count)
        }
    }

    private fun pollStatus(forceLog: Boolean = false) {
        statusBuffer.clear()
        if (!nativeAgentStatus(statusBuffer)) {
            return
        }
        statusBuffer.position(0)
        val abi = statusBuffer.short.toInt()
        val size = statusBuffer.short.toInt()
        if (abi != 1 || size != STATUS_BYTES) {
            Timber.e("Agent status ABI mismatch (abi=%d size=%d)", abi, size)
            return
        }
        val state = statusBuffer.int
        val epoch = statusBuffer.int.toUInt().toLong()
        statusBuffer.int // reserved padding
        val next = AgentStatus(
            state = AgentState.fromWire(state),
            epoch = epoch,
            vadOnsets = statusBuffer.long,
            turnsCommitted = statusBuffer.long,
            utterancesCompleted = statusBuffer.long,
            bargeIns = statusBuffer.long,
            egressedSamples = statusBuffer.long,
            voicedSamples = statusBuffer.long
        )
        val previous = _status.value
        if (forceLog ||
            next.state != previous.state ||
            next.turnsCommitted != previous.turnsCommitted ||
            next.bargeIns != previous.bargeIns ||
            next.utterancesCompleted != previous.utterancesCompleted
        ) {
            Timber.i(
                "Agent %s epoch=%d onsets=%d turns=%d done=%d bargeIns=%d voiced=%d",
                next.state, next.epoch, next.vadOnsets, next.turnsCommitted,
                next.utterancesCompleted, next.bargeIns, next.voicedSamples
            )
        }
        _status.value = next
    }

    private fun amplitudeSquare(amplitude: Int): ShortArray {
        return ShortArray(FRAME_SAMPLES) { i ->
            if ((i / 8) % 2 == 0) amplitude.toShort() else (-amplitude).toShort()
        }
    }

    private fun silence(): ShortArray = ShortArray(FRAME_SAMPLES)

    private external fun nativeAgentInitialize(): Boolean
    private external fun nativeAgentShutdown()
    private external fun nativeAgentReset()
    private external fun nativeAgentStatus(buffer: ByteBuffer): Boolean
    private external fun nativeInjectCapture(pcm: ShortArray, monoNs: Long, rtpTs: Int): Boolean
    private external fun nativeInterrupt(epoch: Long, reason: String)

    private companion object {
        const val STATUS_BYTES = 64
        const val STATUS_POLL_MS = 100L
        const val FRAME_SAMPLES = 160
        const val FRAME_MS = 10L

        init {
            System.loadLibrary("triplex-native")
        }
    }
}
