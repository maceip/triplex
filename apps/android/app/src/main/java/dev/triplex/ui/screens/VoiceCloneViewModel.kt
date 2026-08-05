package dev.triplex.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.repository.Result
import dev.triplex.voice.ReferenceAudio
import dev.triplex.voice.VoiceCloneRepository
import dev.triplex.voice.VoiceRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt

/** The sentence the user reads aloud: consent record and clone reference. */
const val CONSENT_STATEMENT =
    "I consent to Triplex creating a clone of my voice from this recording, " +
        "for my own use. Today I am setting up my voice profile, and this " +
        "sentence is my reference sample."

private const val DEFAULT_PREVIEW_TEXT =
    "This is my cloned voice speaking a sentence I never actually said. " +
        "The reservation moved to eight fifteen, and the confirmation " +
        "number ends in four two seven."

enum class VoiceCloneStage { IDLE, RECORDING, PREPARING, READY, SYNTHESIZING, PLAYING }

data class VoiceCloneState(
    val stage: VoiceCloneStage = VoiceCloneStage.IDLE,
    val hasProfile: Boolean = false,
    val placement: String = "REMOTE_TTS",
    val placementReason: String = "",
    val referenceSeconds: Int? = null,
    val lastCaptureSeconds: Float? = null,
    val captureSnrDb: Float? = null,
    val previewText: String = DEFAULT_PREVIEW_TEXT,
    val error: String? = null,
    val message: String? = null
) {
    val busy: Boolean
        get() = stage == VoiceCloneStage.PREPARING || stage == VoiceCloneStage.SYNTHESIZING
}

data class VoiceCaptureMeter(
    val amplitude: Float = 0f,
    val elapsedMillis: Long = 0L,
    val maximumSeconds: Int = VoiceRecorder.MAX_SECONDS.toInt()
) {
    val elapsedSeconds: Float
        get() = elapsedMillis / 1_000f

    val durationProgress: Float
        get() = if (maximumSeconds <= 0) {
            0f
        } else {
            (elapsedSeconds / maximumSeconds).coerceIn(0f, 1f)
        }
}

/** Maps capture RMS into a stable visual range without altering recorded PCM. */
internal fun normalizeVoiceRms(rms: Double): Float {
    if (!rms.isFinite() || rms <= 90.0) return 0f
    return sqrt((rms - 90.0) / 6_000.0).toFloat().coerceIn(0f, 1f)
}

@HiltViewModel
class VoiceCloneViewModel @Inject constructor(
    application: Application,
    private val repository: VoiceCloneRepository,
    private val recorder: VoiceRecorder
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VoiceCloneState())
    val state = _state.asStateFlow()

    private val _captureMeter = MutableStateFlow(VoiceCaptureMeter())
    val captureMeter = _captureMeter.asStateFlow()

    private var recordJob: Job? = null
    private var meterJob: Job? = null
    @Volatile private var discardCurrentCapture = false

    private val referenceFile: File
        get() = File(getApplication<Application>().filesDir, "voice_reference.wav")

    private val previewFile: File
        get() = File(getApplication<Application>().cacheDir, "voice_preview.wav")

    private val pendingReferenceFile: File
        get() = File(getApplication<Application>().cacheDir, "voice_reference_pending.wav")

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val status = repository.status() ?: return@launch
            _state.value = _state.value.copy(
                hasProfile = status.synthesis_ready,
                placement = status.placement,
                placementReason = status.placement_reason,
                referenceSeconds = status.reference_seconds,
                stage = if (status.synthesis_ready) VoiceCloneStage.READY else _state.value.stage
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordJob != null) return
        discardCurrentCapture = false
        pendingReferenceFile.delete()
        _captureMeter.value = VoiceCaptureMeter()
        _state.value = _state.value.copy(
            stage = VoiceCloneStage.RECORDING,
            error = null,
            message = null
        )
        startMetering()
        recordJob = viewModelScope.launch {
            val captureResult = runCatching {
                recorder.record(pendingReferenceFile)
            }
            stopMetering()
            recordJob = null

            if (discardCurrentCapture) {
                discardCurrentCapture = false
                pendingReferenceFile.delete()
                _captureMeter.value = VoiceCaptureMeter()
                _state.value = _state.value.copy(
                    stage = restingStage(),
                    error = null,
                    message = null
                )
                return@launch
            }

            val result = captureResult.getOrElse { failure ->
                Timber.e(failure, "Recording failed")
                pendingReferenceFile.delete()
                _state.value = _state.value.copy(
                    stage = restingStage(),
                    error = "Recording failed: ${failure.message}"
                )
                return@launch
            }

            // The quality gate runs on-device: a reference that cannot produce
            // a good voice is rejected here, with specific guidance, instead of
            // being uploaded and burning model time.
            when (result) {
                is ReferenceAudio.Result.Usable -> {
                    _state.value = _state.value.copy(
                        lastCaptureSeconds = result.quality.durationSeconds,
                        captureSnrDb = result.quality.snrDb,
                        error = null
                    )
                    prepare(result.quality.durationSeconds, pendingReferenceFile)
                }
                is ReferenceAudio.Result.Unusable -> {
                    pendingReferenceFile.delete()
                    _state.value = _state.value.copy(
                        stage = restingStage(),
                        lastCaptureSeconds = result.quality.durationSeconds,
                        captureSnrDb = result.quality.snrDb,
                        error = result.reason
                    )
                }
            }
        }
    }

    fun stopRecording() {
        recorder.stop()
    }

    /** Stops and discards only the pending sample when enrollment is exited. */
    fun discardRecording() {
        if (recordJob == null) return
        discardCurrentCapture = true
        recorder.stop()
    }

    fun onMicrophonePermissionDenied() {
        _state.value = _state.value.copy(
            stage = restingStage(),
            error = "Microphone access is required to create your voice profile."
        )
    }

    private fun restingStage(): VoiceCloneStage = if (_state.value.hasProfile) {
        VoiceCloneStage.READY
    } else {
        VoiceCloneStage.IDLE
    }

    private fun startMetering() {
        meterJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        meterJob = viewModelScope.launch {
            while (isActive && _state.value.stage == VoiceCloneStage.RECORDING) {
                _captureMeter.value = VoiceCaptureMeter(
                    amplitude = normalizeVoiceRms(recorder.liveRms),
                    elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                )
                delay(METER_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel()
        meterJob = null
        _captureMeter.value = _captureMeter.value.copy(amplitude = 0f)
    }

    private suspend fun prepare(seconds: Float, preparedReference: File) {
        _state.value = _state.value.copy(stage = VoiceCloneStage.PREPARING, error = null)
        val result = repository.uploadConsentedReference(
            reference = preparedReference,
            consentStatement = CONSENT_STATEMENT,
            seconds = seconds.toInt().coerceIn(3, 30)
        )
        _state.value = when (result) {
            is Result.Success -> {
                runCatching { preparedReference.copyTo(referenceFile, overwrite = true) }
                    .onFailure { Timber.w(it, "Prepared reference could not be retained locally") }
                preparedReference.delete()
                _state.value.copy(
                    stage = VoiceCloneStage.READY,
                    hasProfile = result.data.synthesis_ready,
                    placement = result.data.placement,
                    placementReason = result.data.placement_reason,
                    referenceSeconds = result.data.reference_seconds,
                    message = "Voice ready"
                )
            }
            is Result.Error -> {
                preparedReference.delete()
                _state.value.copy(
                    stage = restingStage(),
                    error = result.message
                )
            }
        }
    }

    fun updatePreviewText(text: String) {
        _state.value = _state.value.copy(previewText = text)
    }

    fun speakPreview() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                stage = VoiceCloneStage.SYNTHESIZING,
                error = null,
                message = null
            )
            when (val result = repository.preview(_state.value.previewText, previewFile)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(stage = VoiceCloneStage.PLAYING)
                    recorder.play(result.data)
                    _state.value = _state.value.copy(
                        stage = VoiceCloneStage.READY,
                        message = "Played in your cloned voice"
                    )
                }
                is Result.Error -> _state.value = _state.value.copy(
                    stage = VoiceCloneStage.READY,
                    error = result.message
                )
            }
        }
    }

    fun playReference() {
        viewModelScope.launch {
            if (!referenceFile.isFile) {
                _state.value = _state.value.copy(error = "No local recording on this device")
                return@launch
            }
            val previous = _state.value.stage
            _state.value = _state.value.copy(stage = VoiceCloneStage.PLAYING)
            recorder.play(referenceFile)
            _state.value = _state.value.copy(stage = previous)
        }
    }

    fun revoke() {
        viewModelScope.launch {
            when (val result = repository.revoke()) {
                is Result.Success -> {
                    referenceFile.delete()
                    previewFile.delete()
                    pendingReferenceFile.delete()
                    _state.value = VoiceCloneState(message = "Voice profile revoked")
                }
                is Result.Error -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    override fun onCleared() {
        discardCurrentCapture = true
        recorder.stop()
        meterJob?.cancel()
        pendingReferenceFile.delete()
        super.onCleared()
    }

    private companion object {
        const val METER_INTERVAL_MILLIS = 33L
    }
}
