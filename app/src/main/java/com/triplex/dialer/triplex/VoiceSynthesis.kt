package com.triplex.dialer.triplex

import com.triplex.dialer.Constants
import com.triplex.dialer.model.VoiceCloneProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Dual-model voice synthesis engine — Kokoro (small/fast) + Qwen3-TTS (large/quality).
 *
 * Architecture derived from Triplex's voice_synthesis.py and cosmo's LLM routing:
 * - Model A (Kokoro): on-device TTS, 10-50 ms per frame, used for greetings and
 *   quick confirmations. 16 kHz PCM output.
 * - Model B (Qwen3-TTS): cloud/edge TTS, higher quality, used for full responses.
 *   Streams back PCM frames as they decode.
 *
 * Routing logic:
 * - Greeting path (first 1-2 seconds of call): always Kokoro for instant response
 * - Short utterance (< 10 tokens): Kokoro, no need to invoke large model
 * - Full response (> 10 tokens): Qwen3-TTS for natural prosody
 * - Interruption recovery: Kokoro to bridge gap while large model re-plans
 *
 * Hyper-low-latency contract:
 * - Kokoro: first PCM frame within 50 ms of text input
 * - Qwen3: first PCM frame within 150 ms (streaming decode)
 * - Frame pacing: exactly 20 ms per frame (320 samples @ 16 kHz)
 */
class VoiceSynthesis(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _activeModel = MutableStateFlow("kokoro") // "kokoro" | "qwen3"
    val activeModel: StateFlow<String> = _activeModel

    private var synthesisJob: Job? = null

    /** Callback for PCM frame output. Set by TriplexClient. */
    var onFrame: ((ShortArray) -> Unit)? = null

    /** Callback for synthesis complete. Set by TriplexClient. */
    var onComplete: (() -> Unit)? = null

    /** Active voice clone profile (or null for default voice). */
    var cloneProfile: VoiceCloneProfile? = null

    // ─── Public API ───────────────────────────────────────────────

    /**
     * Synthesize text to PCM frames.
     *
     * @param text The text to speak
     * @param isUrgent If true, use Kokoro only (greeting/interruption recovery)
     * @param useClone Apply voice clone profile if available
     */
    fun speak(text: String, isUrgent: Boolean = false, useClone: Boolean = true) {
        cancel()
        _isSpeaking.value = true

        synthesisJob = scope.launch(Dispatchers.IO) {
            val model = selectModel(text, isUrgent)
            _activeModel.value = model

            when (model) {
                "kokoro" -> synthesizeKokoro(text)
                "qwen3" -> synthesizeQwen3(text)
            }

            _isSpeaking.value = false
            onComplete?.invoke()
        }
    }

    /** Cancel current synthesis (for interruption/barge-in). */
    fun cancel() {
        synthesisJob?.cancel()
        synthesisJob = null
        _isSpeaking.value = false
    }

    /**
     * Select voice synthesis engine.
     *
     * @param profile Voice clone profile or null for default Kokoro.
     * @param modelName Model name override ("kokoro" | "qwen3"), null for auto-select.
     */
    fun selectEngine(profile: VoiceCloneProfile?, modelName: String? = null) {
        cloneProfile = profile
        if (modelName != null) {
            _activeModel.value = modelName
        }
    }

    /**
     * Pause AI speech — stops synthesis without clearing the clone profile.
     * Used when requesting user input (yank).
     */
    fun pause() {
        cancel()
    }

    /**
     * Resume AI speech — speak the given answer (or null to skip).
     * Called after user provides input via yank dialog.
     */
    fun resume(answer: String? = null) {
        if (answer != null) {
            speak(answer, isUrgent = true)
        } else {
            _isSpeaking.value = false
            onComplete?.invoke()
        }
    }

    /** Reset synthesis state on call end. */
    fun reset() {
        cancel()
        cloneProfile = null
        kokoroOscillator.reset()
    }

    // ─── Model selection ──────────────────────────────────────────

    /**
     * Select model based on utterance length and urgency.
     *
     * Kokoro: < 10 tokens or urgent (greeting/interruption recovery)
     * Qwen3: >= 10 tokens for full response quality
     */
    private fun selectModel(text: String, isUrgent: Boolean): String {
        if (isUrgent) return "kokoro"
        val tokenEstimate = text.length / 3  // rough character → token
        return if (tokenEstimate < 10) "kokoro" else "qwen3"
    }

    // ─── Kokoro (small/fast on-device TTS) ────────────────────────

    /**
     * Kokoro TTS: lightweight phoneme → PCM engine.
     *
     * Latency target: 10 ms to first frame, 20 ms frame pacing.
     * Architecture:
     * 1. Text → phoneme sequence (local phonemizer)
     * 2. Phoneme → audio tokens (small Transformer)
     * 3. Audio tokens → PCM (vocoder / WaveRNN)
     * 4. Frame callback at 20 ms boundaries
     */
    private suspend fun synthesizeKokoro(text: String) {
        // Phase 1: Phonemize
        val phonemes = phonemize(text)
        if (phonemes.isEmpty()) return

        // Phase 2: Generate audio tokens frame-by-frame
        val totalDurationMs = estimateDuration(phonemes)
        val totalFrames = totalDurationMs / AudioPipeline.FRAME_DURATION_MS

        for (frameIdx in 0 until totalFrames) {
            checkInterruption()

            // Generate one 20 ms PCM frame
            val frame = generateKokoroFrame(phonemes, frameIdx)
            onFrame?.invoke(frame)

            // Pace at real-time: 20 ms per frame
            delay(AudioPipeline.FRAME_DURATION_MS.toLong())
        }
    }

    /** Phonemize text to phoneme sequence. */
    private fun phonemize(text: String): List<String> {
        // Simplified: character-based phoneme split
        // Real impl: espeak-ng or deep-phonemizer
        return text.map { c -> c.uppercase() }
    }

    /** Estimate total duration from phoneme count. */
    private fun estimateDuration(phonemes: List<String>): Int {
        val phonemeRate = 12 // phonemes per second at normal speech rate
        return (phonemes.size * 1000) / phonemeRate
    }

    // Preallocated buffer reused across all Kokoro frame renders — no allocations in hot loop
    private val kokoroBuffer = FloatArray(AudioPipeline.FRAME_SIZE_SAMPLES)

    // Singleton oscillator — state persists across frames for continuous phase
    private val kokoroOscillator = SineOscillator(AudioPipeline.SAMPLE_RATE)

    /** Generate one frame of PCM from Kokoro model. */
    private suspend fun generateKokoroFrame(
        phonemes: List<String>,
        frameIdx: Int,
    ): ShortArray {
        // Real impl: Kokoro's small Transformer + vocoder
        // Placeholder: block-rendered sine wave via SineOscillator
        val freq = 200.0
        val gain = 0.8f

        // Render into preallocated float buffer
        kokoroOscillator.render(
            output = kokoroBuffer,
            offset = 0,
            frames = AudioPipeline.FRAME_SIZE_SAMPLES,
            frequencyHz = freq,
            gain = gain,
        )

        // Convert FloatArray → ShortArray (16-bit PCM)
        val frame = ShortArray(AudioPipeline.FRAME_SIZE_SAMPLES)
        for (i in frame.indices) {
            frame[i] = (kokoroBuffer[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return frame
    }

    // ─── Qwen3-TTS (cloud/edge quality TTS) ───────────────────────

    /** OkHttp client for Qwen3-TTS HTTP/WebSocket. */
    private val ttsHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // streaming may be long
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Qwen3-TTS: streaming cloud TTS via WebSocket or HTTP streaming.
     *
     * Latency target: 150 ms to first frame.
     * Architecture:
     * 1. Send text to Qwen3-TTS endpoint via HTTP POST
     * 2. Open WebSocket to receive streaming PCM chunks
     * 3. Split into 20 ms frames and dispatch
     * 4. Handle backpressure via frame pacing
     */
    private suspend fun synthesizeQwen3(text: String) {
        // Phase 1: Send TTS request, get streaming session URL
        val sessionUrl = sendTtsRequest(text)

        // Phase 2: Stream PCM chunks back over WebSocket
        val stream = openTtsStream(sessionUrl)

        try {
            for (chunk in stream) {
                checkInterruption()

                // Chunk may be larger than one frame; split into 20 ms frames
                val frames = splitIntoFrames(chunk)
                for (frame in frames) {
                    onFrame?.invoke(frame)
                    delay(AudioPipeline.FRAME_DURATION_MS.toLong())
                }
            }
        } finally {
            closeTtsStream()
        }
    }

    /** Qwen3-TTS API types. */
    @Serializable
    data class TtsRequest(
        val text: String,
        val voice: String = "default",
        val sampleRate: Int = AudioPipeline.SAMPLE_RATE,
        val format: String = "pcm_s16le",
        val streaming: Boolean = true,
    )

    @Serializable
    data class TtsResponse(
        val sessionId: String = "",
        val streamUrl: String = "",
        val error: String? = null,
    )

    /** Send TTS request to Qwen3 endpoint via HTTP POST. Returns WebSocket streaming URL. */
    private suspend fun sendTtsRequest(text: String): String {
        return withContext(Dispatchers.IO) {
            val mediaType = "application/json".toMediaType()
            val requestBody = TtsRequest(
                text = text,
                voice = cloneProfile?.displayName ?: "default",
                sampleRate = AudioPipeline.SAMPLE_RATE,
                format = "pcm_s16le",
                streaming = true,
            )
            val jsonStr = json.encodeToString(requestBody)
            val body = jsonStr.toRequestBody(mediaType)
            val request = okhttp3.Request.Builder()
                .url("${Constants.TRIPLEX_API_BASE}/tts/qwen3")
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            val response = ttsHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("Qwen3-TTS: HTTP ${response.code}")
            }
            val responseBody = response.body?.string().orEmpty()
            val ttsResp = json.decodeFromString<TtsResponse>(responseBody)
            val streamUrl = ttsResp.streamUrl.takeIf { it.isNotEmpty() }
                ?: throw RuntimeException("Qwen3-TTS: empty streamUrl")
            streamUrl
        }
    }

    /** WebSocket state for streaming PCM. */
    private var ttsWebSocket: WebSocket? = null

    /** Open streaming PCM chunk channel via WebSocket. Returns channel of PCM chunks. */
    private suspend fun openTtsStream(sessionUrl: String): List<ByteArray> {
        val chunks = ConcurrentLinkedQueue<ByteArray>()
        val latch = CompletableDeferred<Unit>()

        val wsUrl = sessionUrl.toHttpUrl()

        val wsRequest = Request.Builder().url(wsUrl).build()
        val ws = ttsHttpClient.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Connection established
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary message = PCM chunk
                chunks.offer(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text message = session event (ignore for now)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                latch.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.completeExceptionally(t)
            }
        })

        ttsWebSocket = ws

        // Wait for stream to close, collecting chunks in the meantime
        latch.await()
        ttsWebSocket = null
        return chunks.toList()
    }

    /** Close TTS WebSocket connection. */
    private suspend fun closeTtsStream() {
        withContext(Dispatchers.IO) {
            ttsWebSocket?.close(1000, "stream complete")
            ttsWebSocket = null
        }
    }

    /** Split a PCM chunk into 20 ms frames. */
    private fun splitIntoFrames(chunk: ByteArray): List<ShortArray> {
        val numFrames = chunk.size / AudioPipeline.FRAME_SIZE_BYTES
        if (numFrames == 0) return emptyList()

        val frames = mutableListOf<ShortArray>()
        for (i in 0 until numFrames) {
            val offset = i * AudioPipeline.FRAME_SIZE_BYTES
            val frameBytes = chunk.copyOfRange(offset, offset + AudioPipeline.FRAME_SIZE_BYTES)
            val shortFrame = ShortArray(AudioPipeline.FRAME_SIZE_SAMPLES)
            for (j in shortFrame.indices) {
                val lo = frameBytes[j * 2].toInt() and 0xFF
                val hi = frameBytes[j * 2 + 1].toInt() and 0xFF
                shortFrame[j] = (lo or (hi shl 8)).toShort()
            }
            frames.add(shortFrame)
        }
        return frames
    }

    // ─── Utilities ────────────────────────────────────────────────

    /** Check if synthesis was interrupted (cancel called). */
    private suspend fun checkInterruption() {
        if (!_isSpeaking.value) {
            throw CancellationException("Synthesis interrupted")
        }
    }
}
