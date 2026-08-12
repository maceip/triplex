package dev.triplex.ui.agent

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import dev.triplex.speech.tts.InflectCallVoice
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.journey.FloatingShapeBackdrop
import dev.triplex.ui.journey.JourneyHero
import dev.triplex.ui.journey.JourneyScreenColumn
import dev.triplex.ui.journey.JourneyStageRail
import dev.triplex.ui.journey.JourneyStagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.call.VoiceOrb
import zed.rainxch.rikkaui.components.ui.call.VoiceOrbState
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.sqrt

enum class VoiceLabStage {
    READY,
    SPEAKING,
    LISTENING,
    REPLYING,
    DONE,
    ERROR,
}

data class VoiceLabState(
    val stage: VoiceLabStage = VoiceLabStage.READY,
    val status: String = "Practice the agent voice on this phone — no Plivo line required.",
    val amplitude: Float = 0f,
    val error: String? = null,
)

/**
 * Local voice lab: Inflect TTS to the speaker + mic amplitude listening.
 *
 * Demonstrates on-device TTS without a SIP media path. Does not claim to screen
 * live SIM calls — Android still withholds that audio from any dialer.
 */
@HiltViewModel
class VoiceLabViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val brandedVoice: InflectCallVoice,
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceLabState())
    val state = _state.asStateFlow()

    private var sessionJob: Job? = null
    @Volatile private var amplitude = 0f

    fun startPractice() {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            try {
                runPractice()
            } catch (error: Exception) {
                Timber.e(error, "Voice lab practice failed")
                _state.value = VoiceLabState(
                    stage = VoiceLabStage.ERROR,
                    status = "Practice could not finish.",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun reset() {
        sessionJob?.cancel()
        brandedVoice.cancel()
        sessionJob = null
        amplitude = 0f
        _state.value = VoiceLabState()
    }

    private suspend fun runPractice() {
        _state.value = _state.value.copy(
            stage = VoiceLabStage.SPEAKING,
            status = "Triplex is greeting you…",
            error = null,
        )
        playUtterance(OPENING)

        _state.value = _state.value.copy(
            stage = VoiceLabStage.LISTENING,
            status = "Your turn — say something. The orb follows your mic.",
        )
        listenFor(LISTEN_MS)

        _state.value = _state.value.copy(
            stage = VoiceLabStage.REPLYING,
            status = "Triplex is answering…",
            amplitude = 0f,
        )
        playUtterance(REPLY)

        _state.value = _state.value.copy(
            stage = VoiceLabStage.DONE,
            status = "That was a local practice turn. Live call screening still needs your Triplex line.",
        )
    }

    private suspend fun playUtterance(text: String) {
        brandedVoice.synthesizeStream(text).collect { chunk ->
            playPcm(chunk)
        }
    }

    private suspend fun playPcm(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(pcm.size * 2, SAMPLE_RATE / 10))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = (pcm.size * 1000L) / SAMPLE_RATE
            delay(durationMs + 80L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun listenFor(durationMs: Long) = withContext(Dispatchers.IO) {
        val minBuffer = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) error("Microphone unavailable")
        val recorder = android.media.AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, SAMPLE_RATE / 5) * 2,
        )
        check(recorder.state == android.media.AudioRecord.STATE_INITIALIZED) {
            "Microphone failed to open"
        }
        val buffer = ShortArray(SAMPLE_RATE / 50)
        val deadline = android.os.SystemClock.elapsedRealtime() + durationMs
        try {
            recorder.startRecording()
            while (isActive && android.os.SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toDouble()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read) / Short.MAX_VALUE
                    amplitude = (amplitude * 0.7f) + (rms.toFloat() * 0.3f)
                    _state.value = _state.value.copy(amplitude = amplitude.coerceIn(0f, 1f))
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    override fun onCleared() {
        reset()
        super.onCleared()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val LISTEN_MS = 4_000L
        const val OPENING =
            "Hi — this is Triplex practicing on your phone. Speak after the tone, and I'll reply."
        const val REPLY =
            "Got it. On a real Triplex call I would keep going from here. Your SIM line still needs call forwarding for live screening."
    }
}

@Composable
fun VoiceLabScreen(
    onBack: () -> Unit,
    onOpenCallForward: () -> Unit,
    viewModel: VoiceLabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = RikkaTheme.spacing
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startPractice()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    val orbState = when (state.stage) {
        VoiceLabStage.SPEAKING, VoiceLabStage.REPLYING -> VoiceOrbState.Speaking
        VoiceLabStage.LISTENING -> VoiceOrbState.Listening
        VoiceLabStage.ERROR -> VoiceOrbState.Idle
        else -> VoiceOrbState.Idle
    }
    val stageIndex = when (state.stage) {
        VoiceLabStage.READY -> 0
        VoiceLabStage.SPEAKING -> 1
        VoiceLabStage.LISTENING -> 2
        VoiceLabStage.REPLYING, VoiceLabStage.DONE -> 3
        VoiceLabStage.ERROR -> 0
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TriplexTopBar(
                title = "Voice lab",
                navigationIcon = RikkaIcons.ArrowLeft,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        FloatingShapeBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.xxl),
            ) {
                JourneyScreenColumn {
                    JourneyStagger(0) {
                        JourneyHero(
                            brand = "TRIPLEX",
                            title = "Try the agent voice here.",
                            supporting = "On-device speech synthesis on this handset. " +
                                "No SIP line, and not a live call screen.",
                            eyebrow = "PRACTICE · NO PLIVO REQUIRED",
                        )
                    }
                    JourneyStagger(1) {
                        JourneyStageRail(
                            stages = listOf("Ready", "Speak", "Listen", "Reply"),
                            currentIndex = stageIndex,
                        )
                    }
                    JourneyStagger(2) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            val amp = state.amplitude
                            VoiceOrb(
                                state = orbState,
                                size = 168.dp,
                                amplitude = { amp },
                                label = state.status,
                            )
                        }
                    }
                    JourneyStagger(3) {
                        TriplexCard(
                            modifier = Modifier.fillMaxWidth(),
                            tone = if (state.stage == VoiceLabStage.ERROR) {
                                TriplexCardTone.DANGER
                            } else {
                                TriplexCardTone.DEFAULT
                            },
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                Text(text = state.status, variant = TextVariant.P)
                                state.error?.let {
                                    Text(
                                        text = it,
                                        variant = TextVariant.Small,
                                        color = RikkaTheme.colors.destructive,
                                    )
                                }
                            }
                        }
                    }
                    JourneyStagger(4) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                            when (state.stage) {
                                VoiceLabStage.READY, VoiceLabStage.ERROR -> TriplexButton(
                                    text = "Start practice",
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            viewModel.startPractice()
                                        } else {
                                            permission.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                )
                                VoiceLabStage.DONE -> {
                                    TriplexButton(
                                        text = "Practice again",
                                        onClick = {
                                            viewModel.reset()
                                            viewModel.startPractice()
                                        },
                                    )
                                    TriplexButton(
                                        text = "Set up call forwarding",
                                        onClick = onOpenCallForward,
                                        style = TriplexButtonStyle.SECONDARY,
                                    )
                                }
                                else -> TriplexButton(
                                    text = "Stop",
                                    onClick = viewModel::reset,
                                    style = TriplexButtonStyle.OUTLINE,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
