package com.triplex.dialer.triplex

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.triplex.dialer.Constants
import kotlin.math.pow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

// --- Pure utility functions ------------------------------------------------

/**
 * Convert a 16-bit mono PCM ByteArray (big-endian frame) into a ShortArray.
 * Each pair of bytes (lo, hi) → little-endian short.
 */
fun pcmBytesToShorts(frame: ByteArray): ShortArray {
    val out = ShortArray(frame.size / 2)
    for (i in out.indices) {
        val lo = frame[i * 2].toInt() and 0xFF
        val hi = frame[i * 2 + 1].toInt() and 0xFF
        out[i] = (lo or (hi shl 8)).toShort()
    }
    return out
}

/**
 * Convert a ShortArray of 16-bit PCM samples into a ByteArray (big-endian).
 */
fun pcmShortsToBytes(shorts: ShortArray): ByteArray {
    val out = ByteArray(shorts.size * 2)
    for (i in shorts.indices) {
        val v = shorts[i].toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
    }
    return out
}

/**
 * Compute the peak amplitude of a ShortArray PCM frame.
 * Returns 0 if all samples are silent.
 */
fun peakAmplitude(frame: ShortArray): Int {
    var peak = 0
    for (i in frame.indices) {
        val absVal = kotlin.math.abs(frame[i].toInt())
        if (absVal > peak) peak = absVal
    }
    return peak
}

// --- AudioPipeline class ---------------------------------------------------

class AudioPipeline(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1                     // Mono
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_DURATION_MS = 20
        const val FRAME_SIZE_SAMPLES = 320         // 20 ms of 16 kHz
        const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2  // 640 bytes
        const val BUFFER_SIZE_FACTOR = 4            // 4 frames for jitter
        const val CAPTURE_BUFFER_SIZE = FRAME_SIZE_BYTES * BUFFER_SIZE_FACTOR * 2 // 5120 bytes
        const val PLAYBACK_BUFFER_SIZE = FRAME_SIZE_BYTES * BUFFER_SIZE_FACTOR * 4  // 10240 bytes

        // VAD threshold: 16-bit PCM, compare against -30 dBFS
        val VAD_THRESHOLD = (32768.0 * 10.0.pow(-30.0 / 20.0)).toInt().toShort()
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    // Callbacks registered by TriplexClient
    private var onFrameCaptured: ((ShortArray) -> Unit)? = null
    private var onFramePlayed: ((ShortArray) -> Unit)? = null
    private var onVadTrigger: ((Boolean) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // DTMF injection queue — tones to play during next available playback frames
    private val dtmfQueue = ArrayDeque<ShortArray>()
    private var dtmfPlaying = false

    /** Inject DTMF tones into the playback stream. */
    fun injectDTMF(tone: ShortArray) {
        dtmfQueue.clear()
        dtmfQueue.addLast(tone)
        dtmfPlaying = true
    }

    /** Cancel any queued DTMF. */
    fun clearDTMF() {
        dtmfQueue.clear()
        dtmfPlaying = false
    }

    // Far-end FIFO ring buffer for echo cancellation reference
    private val farEndFifo = FarEndFifo(maxDelayMs = Constants.DEFAULT_ECHO_DELAY_MS)

    // Per-stage profiler
    val profiler = AudioProfiler()

    /** Register frame callbacks. */
    fun setCallbacks(
        onFrameCaptured: ((ShortArray) -> Unit)? = null,
        onFramePlayed: ((ShortArray) -> Unit)? = null,
        onVadTrigger: ((Boolean) -> Unit)? = null,
    ) {
        this.onFrameCaptured = onFrameCaptured
        this.onFramePlayed = onFramePlayed
        this.onVadTrigger = onVadTrigger
    }

    /** Start mic capture + speaker playback. */
    fun start(): Boolean {
        if (_isRunning.value) return false
        if (!initAudioRecord() || !initAudioTrack()) return false

        _isRunning.value = true

        captureJob = scope.launch(Dispatchers.IO) { captureLoop() }
        playbackJob = scope.launch(Dispatchers.IO) { playbackLoop() }
        return true
    }

    fun stop() {
        _isRunning.value = false
        captureJob?.cancel()
        playbackJob?.cancel()
        captureJob = null
        playbackJob = null
        teardownAudioRecord()
        teardownAudioTrack()
        farEndFifo.clear()
    }

    fun reset() {
        stop()
        farEndFifo.clear()
    }

    /** Push a received far-end frame (from Chime bridge) into the FIFO for echo cancellation. */
    fun pushFarEnd(frame: ShortArray) {
        farEndFifo.push(frame)
    }

    /** Pop the matching far-end frame for a given capture frame index. */
    fun popFarEnd(): ShortArray? = farEndFifo.pop()

    // ─── AudioRecord capture ──────────────────────────────────────

    private fun initAudioRecord(): Boolean {
        val recBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            .coerceAtLeast(CAPTURE_BUFFER_SIZE)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(recBufSize)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            return false
        }
        audioRecord = rec
        return true
    }

    private fun captureLoop() {
        val rec = audioRecord ?: return
        val frame = ByteArray(FRAME_SIZE_BYTES)
        rec.startRecording()

        try {
            while (_isRunning.value) {
                val numRead = rec.read(frame, 0, FRAME_SIZE_BYTES)
                if (numRead != FRAME_SIZE_BYTES) continue
                profiler.recordFrame()

                // Stage 1: Convert ByteArray → ShortArray (16-bit PCM)
                val shortFrame = profiler.measure("capture_conversion") {
                    pcmBytesToShorts(frame)
                }

                // Stage 2: VAD threshold check
                profiler.measure("capture_vad") {
                    if (peakAmplitude(shortFrame) > VAD_THRESHOLD.toInt()) {
                        onVadTrigger?.invoke(true)
                    }
                }

                onFrameCaptured?.invoke(shortFrame)
            }
        } finally {
            rec.stop()
        }
    }

    private fun teardownAudioRecord() {
        audioRecord?.let { r ->
            if (r.state == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            r.release()
        }
        audioRecord = null
    }

    // ─── AudioTrack playback ──────────────────────────────────────

    private fun initAudioTrack(): Boolean {
        val pbBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            .coerceAtLeast(PLAYBACK_BUFFER_SIZE)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pbBufSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            return false
        }
        audioTrack = track
        return true
    }

    private fun playbackLoop() {
        val track = audioTrack ?: return
        val frame = ByteArray(FRAME_SIZE_BYTES)
        track.play()

        try {
            while (_isRunning.value) {
                // Check DTMF queue first — play tones if queued
                val dtmfFrame: ShortArray? = if (dtmfPlaying) {
                    dtmfQueue.removeFirstOrNull()
                } else null

                if (dtmfFrame != null) {
                    // Play DTMF tone frame
                    profiler.measure("playback_dtmf") {
                        val converted = pcmShortsToBytes(dtmfFrame)
                        System.arraycopy(converted, 0, frame, 0, FRAME_SIZE_BYTES)
                    }
                    track.write(frame, 0, FRAME_SIZE_BYTES)
                    // If DTMF queue is now empty, mark done
                    if (dtmfQueue.isEmpty()) dtmfPlaying = false
                } else {
                    // Pop far-end frame (echo ref) if available, or wait
                    val farEndFrame = farEndFifo.pop()
                    if (farEndFrame != null) {
                        // Stage 3: Convert ShortArray → ByteArray
                        profiler.measure("playback_conversion") {
                            val converted = pcmShortsToBytes(farEndFrame)
                            System.arraycopy(converted, 0, frame, 0, FRAME_SIZE_BYTES)
                        }

                        // Stage 4: AudioTrack.write()
                        profiler.measure("playback_write") {
                            track.write(frame, 0, FRAME_SIZE_BYTES)
                        }

                        onFramePlayed?.invoke(farEndFrame)
                    } else {
                        // Write silence if no frame available
                        frame.fill(0)
                        track.write(frame, 0, FRAME_SIZE_BYTES)
                    }
                }
            }
        } finally {
            track.stop()
        }
    }

    private fun teardownAudioTrack() {
        audioTrack?.let { t ->
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.release()
        }
        audioTrack = null
    }

    // ─── Far-end FIFO ring buffer ─────────────────────────────────

    class FarEndFifo(maxDelayMs: Int) {
        private val maxFrames = maxDelayMs / FRAME_DURATION_MS
        private val buffer = ArrayDeque<ShortArray>(maxFrames)

        @Synchronized
        fun push(frame: ShortArray) {
            buffer.addLast(frame)
            if (buffer.size > maxFrames) buffer.removeFirst()
        }

        @Synchronized
        fun pop(): ShortArray? = buffer.removeFirstOrNull()

        @Synchronized
        fun clear() = buffer.clear()
    }
}
