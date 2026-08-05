package dev.triplex.nativebridge.audio

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FramePool private constructor(
    private val pool: Array<Frame>,
    private val frameSizeBytes: Int
) {
    private val available = Array(pool.size) { true }
    
    fun acquire(): Frame? {
        for (i in pool.indices) {
            if (available[i]) {
                synchronized(available) {
                    if (available[i]) {
                        available[i] = false
                        pool[i].reset()
                        return pool[i]
                    }
                }
            }
        }
        return null
    }
    
    fun release(frame: Frame) {
        val index = pool.indexOf(frame)
        if (index >= 0) {
            synchronized(available) {
                available[index] = true
            }
        }
    }
    
    fun clear() {
        synchronized(available) {
            for (i in available.indices) {
                available[i] = true
            }
        }
    }
    
    class Frame internal constructor(
        val buffer: ByteBuffer,
        val sizeBytes: Int,
        var timestamp: Long,
        var epoch: Long,
        var flags: Int
    ) {
        fun reset() {
            buffer.clear()
            timestamp = 0L
            epoch = 0L
            flags = 0
        }
        
        fun copyFrom(other: Frame) {
            buffer.clear()
            other.buffer.position(0)
            buffer.put(other.buffer)
            buffer.flip()
            timestamp = other.timestamp
            epoch = other.epoch
            flags = other.flags
        }
    }
    
    class Builder {
        private var poolSize = 32
        private var frameDurationMs = 10
        private var sampleRate = 16000
        private var channels = 1
        
        fun poolSize(size: Int) = apply { this.poolSize = size }
        fun frameDurationMs(duration: Int) = apply { this.frameDurationMs = duration }
        fun sampleRate(rate: Int) = apply { this.sampleRate = rate }
        fun channels(count: Int) = apply { this.channels = count }
        
        fun build(): FramePool {
            val samplesPerFrame = (sampleRate * frameDurationMs) / 1000
            val frameSizeBytes = samplesPerFrame * channels * 2
            
            val pool = Array(poolSize) { i ->
                val buffer = ByteBuffer.allocateDirect(frameSizeBytes)
                buffer.order(ByteOrder.nativeOrder())
                Frame(buffer, frameSizeBytes, 0L, 0L, 0)
            }
            
            Timber.i("FramePool created: $poolSize frames, $frameSizeBytes bytes each")
            return FramePool(pool, frameSizeBytes)
        }
    }
    
    companion object {
        fun builder() = Builder()
    }
}
