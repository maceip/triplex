package dev.triplex.speech.soda

import android.content.Context
import android.os.Build
import com.google.android.libraries.assistant.soda.Soda
import com.google.android.libraries.assistant.soda.SodaCallback
import com.google.android.libraries.assistant.soda.SodaJniLibraryLoader
import com.google.android.libraries.assistant.soda.SodaStopReason
import com.google.speech.soda.ApplicationDomainProto
import com.google.speech.soda.AudioProto
import com.google.speech.soda.ClientInfoProto
import com.google.speech.soda.HybridAsrConfigProto
import com.google.speech.soda.IntendedSpeechConfigProto
import com.google.speech.soda.LoggingProto
import com.google.speech.soda.SodaCoreConfigProto
import com.google.speech.soda.SodaEventProto
import com.google.speech.soda.StatusProto
import com.google.speech.soda.client.SodaClientConfigProto
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class SodaCallTranscriber(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var soda: Soda? = null
    private var committedTranscript = ""

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _status = MutableStateFlow("Preparing on-device speech recognition")
    val status: StateFlow<String> = _status.asStateFlow()

    fun prepare(): Boolean = synchronized(lock) {
        runCatching {
            SodaJniLibraryLoader.ensureLoaded()
            prepareLanguagePack()
            _status.value = "On-device speech recognition is ready"
            true
        }.getOrElse {
            Timber.e(it, "SODA preparation failed")
            _status.value = "On-device speech recognition failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun startCall(): Boolean = synchronized(lock) {
        stopLocked()
        committedTranscript = ""
        _transcript.value = ""
        if (!prepare()) return false
        return runCatching {
            val instance = Soda(appContext, TranscriptCallback())
            val result = instance.initSoda(coreConfig(prepareLanguagePack()))
            check(result.configStatus == StatusProto.ConfigStatus.NO_ERROR) {
                "SODA init returned ${result.configStatus}"
            }
            instance.startCapture(clientConfig())
            soda = instance
            _status.value = "Listening to caller audio on this phone"
            true
        }.getOrElse {
            Timber.e(it, "SODA call session failed")
            _status.value = "On-device transcription failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun addPcm(audio: ByteBuffer, sampleCount: Int) {
        val active = synchronized(lock) { soda } ?: return
        if (sampleCount <= 0) return
        val byteCount = sampleCount * Short.SIZE_BYTES
        audio.position(0)
        audio.limit(byteCount)
        runCatching { active.addAudio(audio, byteCount) }
            .onFailure { Timber.w(it, "SODA addAudio failed") }
    }

    fun stopCall() = synchronized(lock) {
        stopLocked()
        if (_status.value.startsWith("Listening")) {
            _status.value = "Call ended"
        }
    }

    override fun close() = stopCall()

    private fun stopLocked() {
        val active = soda ?: return
        soda = null
        runCatching { active.stopCapture() }
        runCatching { active.delete() }
    }

    private fun prepareLanguagePack(): File {
        val destination = File(appContext.filesDir, "soda/lp_cpu")
        val ready = File(destination, READY_MARKER)
        if (ready.isFile) return destination
        destination.deleteRecursively()
        destination.mkdirs()
        val destinationRoot = destination.canonicalFile
        appContext.assets.open(CPU_PACK_ASSET).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(destination, entry.name).canonicalFile
                    check(target.path.startsWith(destinationRoot.path + File.separator)) {
                        "Unsafe SODA language-pack entry"
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        }
        ready.writeText("ready\n")
        return destination
    }

    private inner class TranscriptCallback : SodaCallback {
        override fun handleStart() = Unit

        override fun handleStop(reason: SodaStopReason) {
            Timber.i("SODA stopped: %s", reason)
        }

        override fun handleShutdown() = Unit

        override fun handleSodaEvent(event: SodaEventProto.SodaEvent) {
            if (!event.hasRecognitionEvent()) return
            val recognition = event.recognitionEvent
            if (recognition.hasFinalResult()) {
                val text = recognition.finalResult.hypothesisList.firstOrNull().orEmpty().trim()
                if (text.isNotBlank()) {
                    committedTranscript = listOf(committedTranscript, text)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    _transcript.value = committedTranscript
                }
            } else if (recognition.hasPartialResult()) {
                val partial = buildString {
                    recognition.partialResult.hypothesisPartsList.forEach { part ->
                        if (part.textCount == 0) return@forEach
                        if (part.leadingSpace && isNotEmpty()) append(' ')
                        append(part.getText(0))
                    }
                }.trim()
                if (partial.isNotBlank()) {
                    _transcript.value = listOf(committedTranscript, partial)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            }
        }
    }

    private fun coreConfig(languagePack: File): SodaCoreConfigProto.SodaCoreConfig {
        val format = AudioProto.RawAudioFormat.newBuilder()
            .setSampleFormat(AudioProto.RawAudioFormat.SampleFormat.INT16)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelCount(1)
            .build()
        return SodaCoreConfigProto.SodaCoreConfig.newBuilder()
            .setCacheDirectory(File(appContext.filesDir, "soda/cache").apply { mkdirs() }.path)
            .setOnDeviceAsrConfig(
                SodaCoreConfigProto.OnDeviceAsrConfig.newBuilder()
                    .setLanguagePackDirectory(languagePack.path)
                    .setApplicationDomain(ApplicationDomainProto.ApplicationDomain.AMBIENT_ONESHOT)
                    .setAttachHotwordEvent(true)
            )
            .setIntendedSpeechConfig(
                IntendedSpeechConfigProto.IntendedSpeechConfig.newBuilder()
                    .setEnabled(true)
                    .setIntentDetectionMode(
                        IntendedSpeechConfigProto.IntendedSpeechConfig.IntentDetectionMode
                            .INTENT_DETECTION_MODE_OPEN_MIC
                    )
                    .setAttachOpenMicResult(true)
            )
            .setAudioInputConfig(
                SodaCoreConfigProto.AudioInputConfig.newBuilder().setMicsAudioFormat(format)
            )
            .setAudioProcessingConfig(
                SodaCoreConfigProto.AudioProcessingConfig.newBuilder().setCachedAudioConfig(
                    SodaCoreConfigProto.CachedAudioConfig.newBuilder().setMaxAudioMs(60_000)
                )
            )
            .setAudioLoggingPolicy(
                LoggingProto.AudioLoggingPolicy.newBuilder().setPolicyType(
                    LoggingProto.AudioLoggingPolicyType.AUDIO_LOGGING_POLICY_TYPE_NONE
                )
            )
            .setServerAsrConfig(HybridAsrConfigProto.ServerAsrConfig.newBuilder().setEnabled(false))
            .setClientInfo(
                ClientInfoProto.ClientInfo.newBuilder()
                    .setApplicationVersion("0.1.0")
                    .setPlatformId("android")
                    .setDeviceModel(Build.MODEL.orEmpty().take(64))
            )
            .build()
    }

    private fun clientConfig(): SodaClientConfigProto.SodaClientConfig {
        val format = AudioProto.RawAudioFormat.newBuilder()
            .setSampleFormat(AudioProto.RawAudioFormat.SampleFormat.INT16)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelCount(1)
            .build()
        val provider = SodaClientConfigProto.EncodedAudioProvider.newBuilder()
            .setAudioFormat(format)
            .setEncodingType(SodaClientConfigProto.EncodedAudioProvider.EncodingType.DEFAULT_RAW)
            .setInputType(SodaClientConfigProto.EncodedAudioProvider.InputType.STREAMING_DATA)
            .build()
        return SodaClientConfigProto.SodaClientConfig.newBuilder()
            .setMicsAudioProviderConfig(
                SodaClientConfigProto.AudioProviderConfig.newBuilder()
                    .setEncodedAudioProvider(provider)
            )
            .setRequireHotword(false)
            .build()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CPU_PACK_ASSET = "soda/lp_cpu.zip"
        const val READY_MARKER = ".ready"
    }
}
