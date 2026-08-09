package dev.triplex.qwenbench

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val TAG = "QwenBench"
        private const val DEFAULT_TEXT =
            "Hello, this is Triplex. May I ask who is calling and what this is regarding?"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Qwen3TtsEngine? = null
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var run: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            text = "Loading optimized Qwen3-TTS..."
            textSize = 18f
        }
        input = EditText(this).apply {
            setText(intent.getStringExtra("text") ?: DEFAULT_TEXT)
            minLines = 3
            textSize = 17f
        }
        run = Button(this).apply {
            text = "Run device benchmark"
            isEnabled = false
            setOnClickListener { runBenchmark(input.text.toString()) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            addView(status)
            addView(input)
            addView(run)
        }
        setContentView(root)

        worker.execute {
            try {
                val started = SystemClock.elapsedRealtime()
                val loaded = Qwen3TtsEngine(filesDir)
                engine = loaded
                val loadMs = SystemClock.elapsedRealtime() - started
                Log.i(
                    TAG,
                    "QWEN_BENCH_READY load_ms=" + loadMs +
                        " device=" + Build.MANUFACTURER + "/" + Build.MODEL,
                )
                runOnUiThread {
                    status.text = "Ready in %.1f s".format(Locale.US, loadMs / 1000.0)
                    run.isEnabled = true
                }
                runBenchmark(intent.getStringExtra("text") ?: DEFAULT_TEXT)
            } catch (t: Throwable) {
                Log.e(TAG, "QWEN_BENCH_LOAD_ERROR", t)
                runOnUiThread { status.text = "Load failed: " + t.message }
            }
        }
    }

    private fun runBenchmark(text: String) {
        if (text.isBlank()) return
        runOnUiThread {
            run.isEnabled = false
            status.text = "Synthesizing on device..."
        }
        worker.execute {
            try {
                val greedy = intent.getBooleanExtra("greedy", false)
                val seed = intent.getLongExtra("seed", 7L)
                val started = SystemClock.elapsedRealtime()
                val result = engine!!.synthesize(
                    text = text,
                    language = "english",
                    greedy = greedy,
                    seed = seed,
                    progress = object : Qwen3TtsEngine.Progress {
                        override fun onFrame(frame: Int) {
                            if (frame % 10 == 0) {
                                Log.i(TAG, "QWEN_BENCH_PROGRESS frames=" + frame)
                            }
                        }
                    },
                )
                val wallMs = SystemClock.elapsedRealtime() - started
                val audioMs = result.audio.size * 1000L / Qwen3TtsEngine.SAMPLE_RATE
                val rtf =
                    if (audioMs == 0L) Double.POSITIVE_INFINITY else wallMs.toDouble() / audioMs
                val wav = File(getExternalFilesDir(null) ?: filesDir, "qwen3-benchmark.wav")
                writeWav(wav, result.audio, Qwen3TtsEngine.SAMPLE_RATE)
                val resultLine =
                    "QWEN_BENCH_RESULT model=qwen3_tts_0_6b_base_fast" +
                        " device=" + Build.MANUFACTURER + "/" + Build.MODEL +
                        " sdk=" + Build.VERSION.SDK_INT +
                        " chars=" + text.length +
                        " greedy=" + greedy +
                        " seed=" + seed +
                        " frames=" + result.frames +
                        " audio_ms=" + audioMs +
                        " wall_ms=" + wallMs +
                        " rtf=" + "%.3f".format(Locale.US, rtf) +
                        " prefill_ms=" + result.prefillMs +
                        " talker_ms=" + result.talkerMs +
                        " mtp_ms=" + result.mtpMs +
                        " codec_ms=" + result.codecMs +
                        " wav=" + wav.absolutePath
                File(filesDir, "benchmark-result.txt").writeText(resultLine)
                Log.i(TAG, resultLine)
                if (intent.getBooleanExtra("autoplay", false)) {
                    play(result.audio)
                }
                runOnUiThread {
                    status.text =
                        "Generated %.2f s in %.2f s\nRTF %.2f\n%s".format(
                            Locale.US,
                            audioMs / 1000.0,
                            wallMs / 1000.0,
                            rtf,
                            wav.name,
                        )
                    run.isEnabled = true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "QWEN_BENCH_RUN_ERROR", t)
                File(filesDir, "benchmark-error.txt").writeText(t.stackTraceToString())
                runOnUiThread {
                    status.text = "Synthesis failed: " + t.message
                    run.isEnabled = true
                }
            }
        }
    }

    private fun play(audio: FloatArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(Qwen3TtsEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(audio.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
        track.play()
    }

    private fun writeWav(file: File, audio: FloatArray, sampleRate: Int) {
        val pcmBytes = audio.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcmBytes)
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(1.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcmBytes)
            out.write(header.array())
            val samples = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio) {
                samples.putShort((sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            }
            out.write(samples.array())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        engine?.close()
    }
}
