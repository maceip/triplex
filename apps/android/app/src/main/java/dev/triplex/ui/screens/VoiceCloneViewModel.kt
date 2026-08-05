package dev.triplex.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.repository.Result
import dev.triplex.voice.ReferenceAudio
import dev.triplex.voice.VoiceCloneRepository
import dev.triplex.voice.VoiceRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

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

@HiltViewModel
class VoiceCloneViewModel @Inject constructor(
    application: Application,
    private val repository: VoiceCloneRepository,
    private val recorder: VoiceRecorder
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VoiceCloneState())
    val state = _state.asStateFlow()

    private var recordJob: Job? = null

    private val referenceFile: File
        get() = File(getApplication<Application>().filesDir, "voice_reference.wav")

    private val previewFile: File
        get() = File(getApplication<Application>().cacheDir, "voice_preview.wav")

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
        _state.value = _state.value.copy(
            stage = VoiceCloneStage.RECORDING,
            error = null,
            message = null
        )
        recordJob = viewModelScope.launch {
            val result = runCatching {
                recorder.record(referenceFile)
            }.getOrElse { failure ->
                Timber.e(failure, "Recording failed")
                _state.value = _state.value.copy(
                    stage = VoiceCloneStage.IDLE,
                    error = "Recording failed: ${failure.message}"
                )
                recordJob = null
                return@launch
            }
            recordJob = null

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
                    prepare(result.quality.durationSeconds)
                }
                is ReferenceAudio.Result.Unusable -> {
                    referenceFile.delete()
                    _state.value = _state.value.copy(
                        stage = VoiceCloneStage.IDLE,
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

    private suspend fun prepare(seconds: Float) {
        _state.value = _state.value.copy(stage = VoiceCloneStage.PREPARING, error = null)
        val result = repository.uploadConsentedReference(
            reference = referenceFile,
            consentStatement = CONSENT_STATEMENT,
            seconds = seconds.toInt().coerceIn(3, 30)
        )
        _state.value = when (result) {
            is Result.Success -> _state.value.copy(
                stage = VoiceCloneStage.READY,
                hasProfile = result.data.synthesis_ready,
                placement = result.data.placement,
                placementReason = result.data.placement_reason,
                referenceSeconds = result.data.reference_seconds,
                message = "Voice ready"
            )
            is Result.Error -> _state.value.copy(
                stage = VoiceCloneStage.IDLE,
                error = result.message
            )
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
                    _state.value = VoiceCloneState(message = "Voice profile revoked")
                }
                is Result.Error -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }
}
