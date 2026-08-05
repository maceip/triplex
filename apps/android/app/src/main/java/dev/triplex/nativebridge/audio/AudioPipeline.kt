package dev.triplex.nativebridge.audio

import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class SpscRing<T : Any> private constructor(
    private val buffer: Array<Any?>,
    private val mask: Int
) {
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)
    
    fun push(item: T): Boolean {
        val currentTail = tail.get()
        val currentHead = head.get()
        
        if (currentTail - currentHead >= buffer.size) {
            return false
        }
        
        buffer[(currentTail and mask.toLong()).toInt()] = item
        tail.lazySet(currentTail + 1)
        return true
    }
    
    fun pop(): T? {
        val currentHead = head.get()
        val currentTail = tail.get()
        
        if (currentTail <= currentHead) {
            return null
        }
        
        val index = (currentHead and mask.toLong()).toInt()
        @Suppress("UNCHECKED_CAST")
        val item = buffer[index] as T?
        buffer[index] = null
        head.lazySet(currentHead + 1)
        return item
    }
    
    fun size(): Int {
        val tailVal = tail.get()
        val headVal = head.get()
        return (tailVal - headVal).toInt().coerceAtLeast(0)
    }
    
    fun isEmpty(): Boolean = tail.get() <= head.get()
    
    fun isFull(): Boolean = tail.get() - head.get() >= buffer.size
    
    fun clear() {
        for (i in buffer.indices) {
            buffer[i] = null
        }
        head.set(0L)
        tail.set(0L)
    }
    
    class Builder {
        private var capacity = 128
        
        fun capacity(cap: Int) = apply {
            this.capacity = Integer.highestOneBit(cap).let {
                if (it < cap) it * 2 else it
            }
        }
        
        fun <T : Any> build(): SpscRing<T> {
            val actualSize = Integer.highestOneBit(capacity).let {
                if (it < capacity) it * 2 else it
            }
            
            Timber.i("SpscRing created: capacity $actualSize")
            return SpscRing(Array(actualSize) { null }, actualSize - 1)
        }
    }
    
    companion object {
        fun builder() = Builder()
    }
}

class AudioPipeline private constructor(
    private val config: AudioPipelineConfig,
    private val captureRing: SpscRing<FramePool.Frame>,
    private val playbackRing: SpscRing<FramePool.Frame>,
    private val framePool: FramePool
) {
    private val state = AtomicReference<State>(State.IDLE)
    private val epoch = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    
    private var nativePipelinePtr: Long = 0L
    
    fun start(epoch: Long): Boolean {
        if (state.get() != State.IDLE) {
            return false
        }
        
        this.epoch.set(epoch)
        state.set(State.STARTING)
        
        val result = nativeStartPipeline(nativePipelinePtr, epoch)
        
        state.set(if (result) State.RUNNING else State.ERROR)
        Timber.i("Audio pipeline ${if (result) "started" else "failed"} (epoch: $epoch)")
        
        return result
    }
    
    fun stop() {
        if (state.get() == State.IDLE) {
            return
        }
        
        state.set(State.STOPPING)
        nativeStopPipeline(nativePipelinePtr)
        state.set(State.IDLE)
        
        captureRing.clear()
        playbackRing.clear()
        framePool.clear()
        
        Timber.i("Audio pipeline stopped (dropped: ${droppedFrames.get()} frames)")
        droppedFrames.set(0L)
    }
    
    fun pushCapture(frame: FramePool.Frame): Boolean {
        if (state.get() != State.RUNNING) {
            return false
        }
        
        val currentEpoch = epoch.get()
        if (frame.epoch != currentEpoch) {
            Timber.w("Frame epoch mismatch: ${frame.epoch} vs $currentEpoch")
            return false
        }
        
        if (!captureRing.push(frame)) {
            droppedFrames.incrementAndGet()
            if (droppedFrames.get() % 10L == 0L) {
                Timber.w("Capture ring full, dropped ${droppedFrames.get()} frames")
            }
            return false
        }
        
        return true
    }
    
    fun popPlayback(): FramePool.Frame? {
        if (state.get() != State.RUNNING) {
            return null
        }
        
        return playbackRing.pop()
    }
    
    fun injectGenerated(audio: ByteBuffer, timestamp: Long, epoch: Long): Boolean {
        if (state.get() != State.RUNNING) {
            return false
        }
        
        val currentEpoch = this.epoch.get()
        if (epoch != currentEpoch) {
            Timber.w("Injection epoch stale: $epoch vs $currentEpoch")
            return false
        }
        
        val frame = framePool.acquire() ?: run {
            Timber.e("No frames available for injection")
            return false
        }
        
        frame.buffer.clear()
        frame.buffer.put(audio)
        frame.buffer.flip()
        frame.timestamp = timestamp
        frame.epoch = epoch
        frame.flags = Flags.GENERATED
        
        if (!playbackRing.push(frame)) {
            framePool.release(frame)
            return false
        }
        
        return true
    }
    
    fun getState(): State = state.get()
    
    fun getDroppedFrames(): Long = droppedFrames.get()
    
    private external fun nativeStartPipeline(ptr: Long, epoch: Long): Boolean
    private external fun nativeStopPipeline(ptr: Long)
    
    enum class State {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
    
    object Flags {
        const val GENERATED = 0x01
        const val INTERRUPTED = 0x02
        const val PARTIAL = 0x04
    }
    
    class Builder {
        private var sampleRate = 16000
        private var channels = 1
        private var frameDurationMs = 10
        private var ringCapacity = 128
        private var poolSize = 32
        private var echoCancellation = true
        private var noiseSuppression = true
        
        fun sampleRate(rate: Int) = apply { this.sampleRate = rate }
        fun channels(count: Int) = apply { this.channels = count }
        fun frameDurationMs(duration: Int) = apply { this.frameDurationMs = duration }
        fun ringCapacity(cap: Int) = apply { this.ringCapacity = cap }
        fun poolSize(size: Int) = apply { this.poolSize = size }
        fun echoCancellation(enabled: Boolean) = apply { this.echoCancellation = enabled }
        fun noiseSuppression(enabled: Boolean) = apply { this.noiseSuppression = enabled }
        
        fun build(): AudioPipeline {
            val framePool = FramePool.builder()
                .poolSize(poolSize)
                .sampleRate(sampleRate)
                .channels(channels)
                .frameDurationMs(frameDurationMs)
                .build()
            
            val captureRing = SpscRing.builder()
                .capacity(ringCapacity)
                .build<FramePool.Frame>()
            
            val playbackRing = SpscRing.builder()
                .capacity(ringCapacity)
                .build<FramePool.Frame>()
            
            val config = AudioPipelineConfig(
                sampleRate = sampleRate,
                channels = channels,
                frameDurationMs = frameDurationMs,
                echoCancellation = echoCancellation,
                noiseSuppression = noiseSuppression
            )
            
            Timber.i("AudioPipeline created: $config")
            return AudioPipeline(config, captureRing, playbackRing, framePool)
        }
    }
    
    companion object {
        fun builder() = Builder()
    }
}

data class AudioPipelineConfig(
    val sampleRate: Int,
    val channels: Int,
    val frameDurationMs: Int,
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean
) {
    val samplesPerFrame: Int = (sampleRate * frameDurationMs) / 1000
    val frameSizeBytes: Int = samplesPerFrame * channels * 2
}
