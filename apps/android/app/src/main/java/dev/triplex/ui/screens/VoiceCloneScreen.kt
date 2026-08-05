package dev.triplex.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.components.TriplexVoiceSphere
import dev.triplex.ui.components.VoiceSpherePhase
import dev.triplex.ui.theme.TriplexDesign
import dev.triplex.ui.theme.TriplexIcons
import java.util.Locale
import kotlin.math.abs

/** Consent-first voice enrollment, preparation, preview, and revocation. */
@Composable
fun VoiceCloneScreen(
    onBack: () -> Unit,
    viewModel: VoiceCloneViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val meter by viewModel.captureMeter.collectAsState()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    VoiceCloneContent(
        state = state,
        meter = meter,
        onBack = onBack,
        onStartCapture = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
        onFinishCapture = viewModel::stopRecording,
        onDiscardCapture = viewModel::discardRecording,
        onUpdatePreviewText = viewModel::updatePreviewText,
        onSpeakPreview = viewModel::speakPreview,
        onPlayReference = viewModel::playReference,
        onRevoke = viewModel::revoke
    )
}

@Composable
internal fun VoiceCloneContent(
    state: VoiceCloneState,
    meter: VoiceCaptureMeter,
    onBack: () -> Unit,
    onStartCapture: () -> Unit,
    onFinishCapture: () -> Unit,
    onDiscardCapture: () -> Unit,
    onUpdatePreviewText: (String) -> Unit,
    onSpeakPreview: () -> Unit,
    onPlayReference: () -> Unit,
    onRevoke: () -> Unit
) {
    var leaveAfterDiscard by remember { mutableStateOf(false) }
    val motion = TriplexDesign.motion

    LaunchedEffect(leaveAfterDiscard, state.stage) {
        if (leaveAfterDiscard && state.stage != VoiceCloneStage.RECORDING) {
            leaveAfterDiscard = false
            onBack()
        }
    }

    val requestBack = {
        if (state.stage == VoiceCloneStage.RECORDING) {
            leaveAfterDiscard = true
            onDiscardCapture()
        } else {
            onBack()
        }
    }
    BackHandler(enabled = state.stage == VoiceCloneStage.RECORDING) {
        requestBack()
    }

    val showEnrollment = !state.hasProfile ||
        state.stage == VoiceCloneStage.RECORDING ||
        state.stage == VoiceCloneStage.PREPARING

    TriplexBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TriplexTopBar(
                    title = if (showEnrollment) "Voice setup" else "Your voice",
                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    navigationContentDescription = if (state.stage == VoiceCloneStage.RECORDING) {
                        "Cancel voice capture"
                    } else {
                        "Back"
                    },
                    onNavigationClick = requestBack
                )
            }
        ) { padding ->
            AnimatedContent(
                targetState = showEnrollment,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                transitionSpec = {
                    (fadeIn(tween(motion.expressiveMillis)) +
                        scaleIn(initialScale = 0.985f)) togetherWith
                        (fadeOut(tween(motion.quickMillis)) +
                            scaleOut(targetScale = 0.99f))
                },
                label = "Voice enrollment mode"
            ) { enrollment ->
                if (enrollment) {
                    VoiceEnrollmentExperience(
                        state = state,
                        meter = meter,
                        leaveAfterDiscard = leaveAfterDiscard,
                        onStartCapture = onStartCapture,
                        onFinishCapture = onFinishCapture
                    )
                } else {
                    VoiceReadyExperience(
                        state = state,
                        onStartCapture = onStartCapture,
                        onUpdatePreviewText = onUpdatePreviewText,
                        onSpeakPreview = onSpeakPreview,
                        onPlayReference = onPlayReference,
                        onRevoke = onRevoke
                    )
                }
            }
        }
    }
}

private enum class EnrollmentUiStage {
    CONSENT,
    RECORDING,
    PREPARING,
    RETRY
}

private data class EnrollmentCopy(
    val eyebrow: String,
    val title: String,
    val description: String,
    val status: String
)

private const val CONSENT_GUIDE_DURATION_MILLIS = 13_600L
private val consentPromptWords = CONSENT_STATEMENT.split(' ')
private val consentPromptWeights = consentPromptWords.map { word ->
    1f + word.length.coerceAtMost(12) * 0.018f + when {
        word.endsWith('.') -> 0.42f
        word.endsWith(',') -> 0.24f
        else -> 0f
    }
}
private val consentPromptTotalWeight = consentPromptWeights.sum()

/** Time-based reading guide. It deliberately does not claim ASR alignment. */
internal data class VoicePromptProgress(
    val activeWordIndex: Int,
    val activeWordFraction: Float,
    val completedWordCount: Int,
    val overallProgress: Float,
    val wordCount: Int
)

internal fun voicePromptProgress(elapsedMillis: Long): VoicePromptProgress {
    val overall = (elapsedMillis.coerceAtLeast(0L).toFloat() / CONSENT_GUIDE_DURATION_MILLIS)
        .coerceIn(0f, 1f)
    val lastIndex = consentPromptWords.lastIndex
    if (overall >= 1f) {
        return VoicePromptProgress(
            activeWordIndex = lastIndex,
            activeWordFraction = 1f,
            completedWordCount = consentPromptWords.size,
            overallProgress = 1f,
            wordCount = consentPromptWords.size
        )
    }

    val targetWeight = overall * consentPromptTotalWeight
    var consumedWeight = 0f
    consentPromptWeights.forEachIndexed { index, wordWeight ->
        if (targetWeight <= consumedWeight + wordWeight) {
            return VoicePromptProgress(
                activeWordIndex = index,
                activeWordFraction = ((targetWeight - consumedWeight) / wordWeight)
                    .coerceIn(0f, 1f),
                completedWordCount = index,
                overallProgress = overall,
                wordCount = consentPromptWords.size
            )
        }
        consumedWeight += wordWeight
    }

    return VoicePromptProgress(lastIndex, 1f, consentPromptWords.size, 1f, consentPromptWords.size)
}

internal fun voicePromptAlignment(
    progress: VoicePromptProgress,
    amplitude: Float
): Float {
    val wordCenter = 1f - abs(progress.activeWordFraction * 2f - 1f)
    return (0.34f + wordCenter * 0.26f + amplitude.coerceIn(0f, 1f) * 0.48f)
        .coerceIn(0f, 1f)
}

@Composable
private fun VoiceEnrollmentExperience(
    state: VoiceCloneState,
    meter: VoiceCaptureMeter,
    leaveAfterDiscard: Boolean,
    onStartCapture: () -> Unit,
    onFinishCapture: () -> Unit
) {
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion
    val stage = when {
        state.stage == VoiceCloneStage.RECORDING -> EnrollmentUiStage.RECORDING
        state.stage == VoiceCloneStage.PREPARING -> EnrollmentUiStage.PREPARING
        state.error != null -> EnrollmentUiStage.RETRY
        else -> EnrollmentUiStage.CONSENT
    }
    val spherePhase = when (stage) {
        EnrollmentUiStage.CONSENT -> VoiceSpherePhase.RESTING
        EnrollmentUiStage.RECORDING -> VoiceSpherePhase.LISTENING
        EnrollmentUiStage.PREPARING -> VoiceSpherePhase.PROCESSING
        EnrollmentUiStage.RETRY -> VoiceSpherePhase.ERROR
    }
    val progressIndex = when (stage) {
        EnrollmentUiStage.CONSENT -> 0
        EnrollmentUiStage.RECORDING -> 1
        EnrollmentUiStage.PREPARING -> 2
        EnrollmentUiStage.RETRY -> if (state.lastCaptureSeconds == null) 0 else 1
    }
    val promptProgress = voicePromptProgress(meter.elapsedMillis)
    val scrollState = rememberScrollState()

    LaunchedEffect(stage) {
        scrollState.scrollTo(0)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 720.dp || stage == EnrollmentUiStage.RECORDING
        val sphereSize = when {
            maxHeight < 720.dp -> 176.dp
            stage == EnrollmentUiStage.RECORDING -> 200.dp
            else -> 232.dp
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = spacing.screenHorizontal)
                    .padding(top = spacing.medium, bottom = 168.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) spacing.large else spacing.xLarge)
            ) {
                TriplexReveal {
                    VoiceEnrollmentProgress(currentIndex = progressIndex)
                }

                TriplexVoiceSphere(
                    phase = spherePhase,
                    amplitude = if (stage == EnrollmentUiStage.RECORDING) meter.amplitude else 0f,
                    alignment = if (stage == EnrollmentUiStage.RECORDING) {
                        voicePromptAlignment(promptProgress, meter.amplitude)
                    } else {
                        1f
                    },
                    modifier = Modifier.size(sphereSize),
                    contentDescription = when (stage) {
                        EnrollmentUiStage.RECORDING -> "Voice sphere reacting to microphone level"
                        EnrollmentUiStage.PREPARING -> "Voice profile preparation in progress"
                        EnrollmentUiStage.RETRY -> "Voice capture needs another attempt"
                        EnrollmentUiStage.CONSENT -> "Voice sphere ready for enrollment"
                    }
                )

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(tween(motion.standardMillis)) +
                            slideInVertically(tween(motion.standardMillis)) { it / 8 }) togetherWith
                            (fadeOut(tween(motion.quickMillis)) +
                                slideOutVertically(tween(motion.quickMillis)) { -it / 10 })
                    },
                    label = "Voice enrollment instruction"
                ) { animatedStage ->
                    val animatedCopy = enrollmentCopy(
                        stage = animatedStage,
                        retryReason = state.error
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.small)
                    ) {
                        Text(
                            text = animatedCopy.eyebrow,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = animatedCopy.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = animatedCopy.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TriplexStatusPill(
                            text = if (animatedStage == EnrollmentUiStage.RECORDING) {
                                "MICROPHONE LIVE · ${meter.elapsedSeconds.toInt()} SEC"
                            } else {
                                animatedCopy.status
                            },
                            tone = when (animatedStage) {
                                EnrollmentUiStage.RECORDING -> TriplexCardTone.ACCENT
                                EnrollmentUiStage.PREPARING -> TriplexCardTone.WARNING
                                EnrollmentUiStage.RETRY -> TriplexCardTone.DANGER
                                EnrollmentUiStage.CONSENT -> TriplexCardTone.DEFAULT
                            },
                            leadingColor = when (animatedStage) {
                                EnrollmentUiStage.RECORDING -> MaterialTheme.colorScheme.secondary
                                EnrollmentUiStage.PREPARING -> TriplexDesign.colors.warning
                                EnrollmentUiStage.RETRY -> MaterialTheme.colorScheme.error
                                EnrollmentUiStage.CONSENT -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                AnimatedContent(
                    targetState = stage == EnrollmentUiStage.PREPARING,
                    transitionSpec = {
                        fadeIn(tween(motion.standardMillis)) togetherWith
                            fadeOut(tween(motion.quickMillis))
                    },
                    label = "Voice enrollment detail"
                ) { preparing ->
                    if (preparing) {
                        VoicePreparationCard()
                    } else {
                        VoiceReadingCard(
                            recording = stage == EnrollmentUiStage.RECORDING,
                            meter = meter,
                            promptProgress = promptProgress
                        )
                    }
                }

                state.error?.takeIf { stage != EnrollmentUiStage.RETRY }?.let { error ->
                    TriplexCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = TriplexCardTone.DANGER
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (stage == EnrollmentUiStage.RETRY && state.lastCaptureSeconds != null) {
                    CaptureQualityCard(
                        seconds = state.lastCaptureSeconds,
                        snrDb = state.captureSnrDb
                    )
                }

                state.message?.let { message ->
                    TriplexCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = TriplexCardTone.SUCCESS
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            VoiceEnrollmentActionBar(
                stage = stage,
                leaveAfterDiscard = leaveAfterDiscard,
                maximumSeconds = meter.maximumSeconds,
                onStartCapture = onStartCapture,
                onFinishCapture = onFinishCapture,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun CaptureQualityCard(seconds: Float, snrDb: Float?) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Text(
                text = "ON-DEVICE QUALITY CHECK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = buildString {
                    append(String.format(Locale.ROOT, "%.1f sec captured", seconds))
                    snrDb?.let {
                        append(String.format(Locale.ROOT, " · %.0f dB signal-to-noise", it))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VoiceEnrollmentActionBar(
    stage: EnrollmentUiStage,
    leaveAfterDiscard: Boolean,
    maximumSeconds: Int,
    onStartCapture: () -> Unit,
    onFinishCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TriplexDesign.spacing
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, background.copy(alpha = 0.97f), background),
                    startY = 0f,
                    endY = 150f
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = spacing.screenHorizontal)
            .padding(top = spacing.xLarge, bottom = spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        when (stage) {
            EnrollmentUiStage.CONSENT,
            EnrollmentUiStage.RETRY -> TriplexButton(
                text = if (stage == EnrollmentUiStage.RETRY) "Try voice capture again" else "Start reading",
                onClick = onStartCapture,
                leadingIcon = TriplexIcons.Microphone,
                modifier = Modifier.fillMaxWidth()
            )
            EnrollmentUiStage.RECORDING -> TriplexButton(
                text = if (leaveAfterDiscard) "Canceling capture" else "Finish recording",
                onClick = onFinishCapture,
                enabled = !leaveAfterDiscard,
                style = TriplexButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )
            EnrollmentUiStage.PREPARING -> VoicePreparingFooter()
        }

        Text(
            text = when (stage) {
                EnrollmentUiStage.RECORDING ->
                    "Recording stops automatically at $maximumSeconds seconds."
                EnrollmentUiStage.PREPARING ->
                    "Keep this screen open while your consented profile is prepared."
                else ->
                    "Used only for your Triplex voice profile · Delete anytime"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VoicePreparingFooter() {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(spacing.medium))
            Text("Preparing your voice", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun VoiceEnrollmentProgress(currentIndex: Int) {
    val spacing = TriplexDesign.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        listOf("CONSENT", "VOICE", "READY").forEachIndexed { index, label ->
            VoiceProgressSegment(
                label = label,
                active = index == currentIndex,
                complete = index < currentIndex,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun VoiceProgressSegment(
    label: String,
    active: Boolean,
    complete: Boolean,
    modifier: Modifier = Modifier
) {
    val targetColor = when {
        complete -> TriplexDesign.colors.success
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(TriplexDesign.motion.standardMillis),
        label = "Voice progress segment"
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TriplexDesign.spacing.small)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active || complete) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
            }
        )
    }
}

@Composable
private fun VoiceReadingCard(
    recording: Boolean,
    meter: VoiceCaptureMeter,
    promptProgress: VoicePromptProgress
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (recording) TriplexCardTone.ACCENT else TriplexCardTone.DEFAULT
    ) {
        Column(
            modifier = Modifier.padding(spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "READ THIS ALOUD",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recording) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (recording) {
                    TriplexStatusPill(
                        text = "LISTENING",
                        tone = TriplexCardTone.ACCENT,
                        leadingColor = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            VoicePromptStatement(
                recording = recording,
                promptProgress = promptProgress,
                amplitude = meter.amplitude
            )
            AnimatedVisibility(visible = recording) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    LinearProgressIndicator(
                        progress = { promptProgress.overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "FOLLOW THE HIGHLIGHT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f)
                        )
                        Text(
                            "WORD ${promptProgress.activeWordIndex + 1} OF ${promptProgress.wordCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePromptStatement(
    recording: Boolean,
    promptProgress: VoicePromptProgress,
    amplitude: Float
) {
    val activePulse by rememberInfiniteTransition(label = "Active voice word pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Active voice word glow"
    )
    val colors = MaterialTheme.colorScheme
    val prompt = if (recording) {
        buildAnnotatedString {
            consentPromptWords.forEachIndexed { index, word ->
                val style = when {
                    index < promptProgress.completedWordCount -> SpanStyle(
                        color = colors.secondary.copy(alpha = 0.80f),
                        fontWeight = FontWeight.Medium
                    )
                    index == promptProgress.activeWordIndex -> SpanStyle(
                        color = colors.onPrimaryContainer,
                        background = colors.secondary.copy(
                            alpha = 0.16f + activePulse * 0.12f +
                                amplitude.coerceIn(0f, 1f) * 0.10f
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    else -> SpanStyle(
                        color = colors.onPrimaryContainer.copy(alpha = 0.43f)
                    )
                }
                withStyle(style) { append(word) }
                if (index != consentPromptWords.lastIndex) append(' ')
            }
        }
    } else {
        buildAnnotatedString { append(CONSENT_STATEMENT) }
    }

    Text(
        text = prompt,
        style = MaterialTheme.typography.bodyLarge,
        fontStyle = if (recording) FontStyle.Normal else FontStyle.Italic,
        color = if (recording) colors.onPrimaryContainer else colors.onSurface
    )
}

@Composable
private fun VoicePreparationCard() {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.large)
        ) {
            Text("Preparing your voice", style = MaterialTheme.typography.titleLarge)
            PreparationRow(
                label = "Consent sample captured",
                status = "DONE",
                color = TriplexDesign.colors.success
            )
            PreparationRow(
                label = "Voice profile preparation",
                status = "IN PROGRESS",
                color = MaterialTheme.colorScheme.primary
            )
            PreparationRow(
                label = "New-phrase preview",
                status = "NEXT",
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun PreparationRow(label: String, status: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TriplexDesign.spacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f)
        )
    }
}

@Composable
private fun VoiceReadyExperience(
    state: VoiceCloneState,
    onStartCapture: () -> Unit,
    onUpdatePreviewText: (String) -> Unit,
    onSpeakPreview: () -> Unit,
    onPlayReference: () -> Unit,
    onRevoke: () -> Unit
) {
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion
    val spherePhase = when (state.stage) {
        VoiceCloneStage.SYNTHESIZING -> VoiceSpherePhase.PROCESSING
        VoiceCloneStage.PLAYING -> VoiceSpherePhase.PLAYING
        else -> VoiceSpherePhase.READY
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 720.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal)
                .padding(top = spacing.medium, bottom = spacing.xxLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) spacing.large else spacing.xLarge)
        ) {
            VoiceEnrollmentProgress(currentIndex = 3)

            TriplexVoiceSphere(
                phase = spherePhase,
                amplitude = 0f,
                alignment = if (state.stage == VoiceCloneStage.PLAYING) 0.68f else 1f,
                modifier = Modifier.size(if (compact) 172.dp else 208.dp),
                contentDescription = when (state.stage) {
                    VoiceCloneStage.SYNTHESIZING -> "Creating a cloned voice preview"
                    VoiceCloneStage.PLAYING -> "Playing cloned voice preview"
                    else -> "Voice profile ready"
                }
            )

            AnimatedContent(
                targetState = state.stage,
                transitionSpec = {
                    fadeIn(tween(motion.standardMillis)) togetherWith
                        fadeOut(tween(motion.quickMillis))
                },
                label = "Voice ready headline"
            ) { stage ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.small)
                ) {
                    TriplexStatusPill(
                        text = when (stage) {
                            VoiceCloneStage.SYNTHESIZING -> "CREATING PREVIEW"
                            VoiceCloneStage.PLAYING -> "PLAYING"
                            else -> "VOICE READY"
                        },
                        tone = if (stage == VoiceCloneStage.SYNTHESIZING) {
                            TriplexCardTone.WARNING
                        } else {
                            TriplexCardTone.SUCCESS
                        },
                        leadingColor = if (stage == VoiceCloneStage.SYNTHESIZING) {
                            TriplexDesign.colors.warning
                        } else {
                            TriplexDesign.colors.success
                        }
                    )
                    Text(
                        text = when (stage) {
                            VoiceCloneStage.SYNTHESIZING -> "Creating your preview."
                            VoiceCloneStage.PLAYING -> "Listen—this is your new voice."
                            else -> "Your voice is ready."
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Try a sentence you never recorded, then compare it with your original sample.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            TriplexCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.xLarge),
                    verticalArrangement = Arrangement.spacedBy(spacing.large)
                ) {
                    Text("Say something new", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextInput(
                        value = state.previewText,
                        onValueChange = onUpdatePreviewText,
                        label = "Text to speak in your voice",
                        singleLine = false,
                        minLines = 3,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TriplexButton(
                        text = when (state.stage) {
                            VoiceCloneStage.SYNTHESIZING -> "Creating preview"
                            VoiceCloneStage.PLAYING -> "Playing your voice"
                            else -> "Speak in my voice"
                        },
                        onClick = onSpeakPreview,
                        loading = state.stage == VoiceCloneStage.SYNTHESIZING,
                        enabled = state.stage == VoiceCloneStage.READY,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TriplexButton(
                        text = "Play original sample",
                        onClick = onPlayReference,
                        style = TriplexButtonStyle.OUTLINE,
                        enabled = state.stage == VoiceCloneStage.READY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            VoiceProfileDetails(state = state)

            state.error?.let { error ->
                TriplexCard(
                    modifier = Modifier.fillMaxWidth(),
                    tone = TriplexCardTone.DANGER
                ) {
                    Text(error, modifier = Modifier.padding(spacing.large))
                }
            }
            if (state.error != null && state.lastCaptureSeconds != null) {
                CaptureQualityCard(
                    seconds = state.lastCaptureSeconds,
                    snrDb = state.captureSnrDb
                )
            }
            state.message?.let { message ->
                TriplexCard(
                    modifier = Modifier.fillMaxWidth(),
                    tone = TriplexCardTone.SUCCESS
                ) {
                    Text(message, modifier = Modifier.padding(spacing.large))
                }
            }

            HorizontalDivider(color = TriplexDesign.colors.glassBorder)

            TriplexButton(
                text = "Record a new sample",
                onClick = onStartCapture,
                style = TriplexButtonStyle.OUTLINE,
                leadingIcon = TriplexIcons.Microphone,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )
            TriplexButton(
                text = "Delete my voice profile",
                onClick = onRevoke,
                style = TriplexButtonStyle.DANGER,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VoiceProfileDetails(state: VoiceCloneState) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Synthesis placement", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(spacing.medium))
                TriplexStatusPill(text = state.placement, tone = TriplexCardTone.ACCENT)
            }
            if (state.placementReason.isNotBlank()) {
                Text(
                    text = formatPlacementReason(state.placementReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
            state.referenceSeconds?.let { seconds ->
                Text(
                    text = "Consent reference · ${seconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
            state.captureSnrDb?.takeIf { state.error == null }?.let { snrDb ->
                Text(
                    text = String.format(
                        Locale.ROOT,
                        "On-device quality check · %.0f dB signal-to-noise",
                        snrDb
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun formatPlacementReason(reason: String): String {
    val trimmed = reason.trim()
    if (trimmed.isEmpty()) return trimmed
    val sentence = trimmed.replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase() else first.toString()
    }
    return if (sentence.last() in ".!?") sentence else "$sentence."
}

private fun enrollmentCopy(
    stage: EnrollmentUiStage,
    retryReason: String? = null
): EnrollmentCopy = when (stage) {
    EnrollmentUiStage.CONSENT -> EnrollmentCopy(
        eyebrow = "ONE-TIME VOICE ENROLLMENT",
        title = "Create a voice that sounds like you.",
        description = "Read one short statement in your normal speaking voice. Natural is better than perfect.",
        status = "READY WHEN YOU ARE"
    )
    EnrollmentUiStage.RECORDING -> EnrollmentCopy(
        eyebrow = "VOICE CAPTURE",
        title = "Keep speaking naturally.",
        description = "The sphere reacts to your voice. Finish the statement, then tap Finish recording.",
        status = "MICROPHONE LIVE"
    )
    EnrollmentUiStage.PREPARING -> EnrollmentCopy(
        eyebrow = "VOICE PREPARATION",
        title = "Turning your sample into your voice.",
        description = "Your phone accepted the recording. Triplex is preparing the cloned profile through the gateway.",
        status = "PREPARING"
    )
    EnrollmentUiStage.RETRY -> EnrollmentCopy(
        eyebrow = "QUICK RETRY",
        title = "Let’s capture that once more.",
        description = retryReason
            ?: "A clear, complete reading gives the voice profile enough detail to sound like you.",
        status = "NEEDS ANOTHER PASS"
    )
}
