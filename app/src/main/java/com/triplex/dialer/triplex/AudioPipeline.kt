package com.triplex.dialer.triplex

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio pipeline bridging Triplex's voice engine to Android audio.
 *
 * Manages:
 * - 16 kHz / 20 ms PCM frame boundaries (Triplex contract)
 * - Far-end FIFO for echo cancellation reference
 * - VAD threshold detection for barge-in
 * - Audio routing between mic, speaker, and Chime WebRTC
 */
class AudioPipeline(private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {

    /** 20 ms of 16 kHz mono PCM = 320 samples * 2 bytes = 640 bytes */
    companion object {
        const val FRAME_SIZE_BYTES = 640
        const val SAMPLE_RATE = 16000
        const val FRAME_DURATION_MS = 20
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private var micCaptureJob: Job? = null
    private var speakerPlaybackJob: Job? = null

    /** Start the audio pipeline: mic capture + speaker playback. */
    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true

        micCaptureJob = scope.launch {
            // Capture 16-bit mono PCM from mic at 20 ms boundaries
            // Push frames into far-end FIFO for echo cancellation
        }

        speakerPlaybackJob = scope.launch {
            // Play received PCM frames from Chime bridge to speaker
        }
    }

    /** Stop the audio pipeline. */
    fun stop() {
        _isRunning.value = false
        micCaptureJob?.cancel()
        speakerPlaybackJob?.cancel()
        micCaptureJob = null
        speakerPlaybackJob = null
    }

    /** Reset pipeline — used on call end or interruption. */
    fun reset() {
        stop()
        // Clear far-end FIFO, reset echo canceller coefficients
    }
}
