package dev.triplex.control

import dev.triplex.nativebridge.audio.AudioPipeline
import dev.triplex.telephony.sip.CallStateInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TurnController @Inject constructor() {
    
    private val micState = AtomicReference<MicState>(MicState.IDLE)
    private val vadEpoch = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)
    
    private val vadChannel = Channel<VadEvent>(Channel.UNLIMITED)
    private val turnChannel = Channel<TurnEvent>(Channel.UNLIMITED)
    
    private val _turnState = MutableStateFlow<TurnState>(TurnState.IDLE)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()
    
    private val _interruption = MutableSharedFlow<InterruptionEvent>()
    val interruption: SharedFlow<InterruptionEvent> = _interruption.asSharedFlow()
    
    private var audioPipeline: AudioPipeline? = null
    private var processingJob: Job? = null
    
    fun initialize(pipeline: AudioPipeline) {
        this.audioPipeline = pipeline
        isProcessing.set(false)
        _turnState.value = TurnState.IDLE
        vadEpoch.set(System.nanoTime())
        
        Logger.i("TurnController initialized (epoch: ${vadEpoch.get()})")
    }
    
    fun startProcessing(scope: CoroutineScope) {
        if (isProcessing.get()) {
            return
        }
        
        isProcessing.set(true)
        processingJob = scope.launch(Dispatchers.IO) {
            vadChannel.let { channel ->
                processVadEvents(channel)
            }
        }
        
        Logger.i("VAD processing started")
    }
    
    fun stopProcessing() {
        isProcessing.set(false)
        processingJob?.cancel()
        processingJob = null
        
        _turnState.value = TurnState.IDLE
        micState.set(MicState.IDLE)
        
        Logger.i("TurnController stopped")
    }
    
    suspend fun handleCallState(callState: CallStateInfo) {
        when (callState) {
            is CallStateInfo.Active -> {
                vadEpoch.set(callState.epoch)
                _turnState.value = TurnState.LISTENING
            }
            is CallStateInfo.Idle, is CallStateInfo.Interrupted -> {
                stopProcessing()
            }
            else -> {}
        }
    }
    
    fun notifyVadEvent(event: VadEvent) {
        if (!isProcessing.get()) {
            return
        }
        
        val currentEpoch = vadEpoch.get()
        if (event.epoch != currentEpoch) {
            return
        }
        
        vadChannel.trySend(event)
    }
    
    fun injectInterruption(reason: String, epoch: Long) {
        val currentEpoch = vadEpoch.get()
        if (epoch != currentEpoch) {
            return
        }
        
        vadEpoch.incrementAndGet()
        
        audioPipeline?.let { pipeline ->
            pipeline.stop()
        }
        
        _interruption.tryEmit(InterruptionEvent(reason, currentEpoch))
        _turnState.value = TurnState.INTERRUPTED
    }
    
    private suspend fun processVadEvents(channel: ReceiveChannel<VadEvent>) {
        channel.consumeEach { event ->
            if (!isProcessing.get()) {
                return@consumeEach
            }
            
            val currentEpoch = vadEpoch.get()
            if (event.epoch != currentEpoch) {
                return@consumeEach
            }
            
            when (event) {
                is VadEvent.SpeechStart -> {
                    micState.set(MicState.SPEAKING)
                    _turnState.value = TurnState.SPEAKING_DETECTED
                }
                is VadEvent.SpeechEnd -> {
                    if (micState.get() == MicState.SPEAKING) {
                        micState.set(MicState.SILENT)
                        _turnState.value = TurnState.TURN_ENDED
                    }
                }
                is VadEvent.Silence -> {
                    micState.compareAndSet(MicState.SILENT, MicState.SILENT)
                }
            }
        }
    }
    
    fun getCurrentEpoch(): Long = vadEpoch.get()
    
    fun getMicState(): MicState = micState.get()
    
    sealed class VadEvent {
        abstract val epoch: Long
        abstract val timestamp: Long
        
        data class SpeechStart(override val epoch: Long, override val timestamp: Long) : VadEvent()
        data class SpeechEnd(override val epoch: Long, override val timestamp: Long) : VadEvent()
        data class Silence(override val epoch: Long, override val timestamp: Long) : VadEvent()
    }
    
    sealed class TurnEvent {
        abstract val epoch: Long
        abstract val timestamp: Long
        
        data class TurnStart(override val epoch: Long, override val timestamp: Long) : TurnEvent()
        data class TurnEnd(override val epoch: Long, override val timestamp: Long) : TurnEvent()
    }
    
    enum class TurnState {
        IDLE,
        LISTENING,
        SPEAKING_DETECTED,
        TURN_ENDED,
        PROCESSING,
        INTERRUPTED
    }
    
    enum class MicState {
        IDLE,
        SILENT,
        SPEAKING
    }
    
    data class InterruptionEvent(
        val reason: String,
        val epoch: Long
    )
    
    companion object {
        private object Logger {
            fun i(message: String) {
                Timber.i("[TurnController] $message")
            }
        }
    }
}
