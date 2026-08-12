package dev.triplex.ui.agent

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.triplexContentWidth
import java.util.Locale
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.Mic
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.call.TranscriptText
import zed.rainxch.rikkaui.components.ui.call.VoiceOrb
import zed.rainxch.rikkaui.components.ui.call.VoiceOrbState
import zed.rainxch.rikkaui.components.ui.call.transcriptHighlight
import zed.rainxch.rikkaui.components.ui.progress.Progress
import zed.rainxch.rikkaui.components.ui.progress.ProgressAnimation
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.separator.Separator
import zed.rainxch.rikkaui.components.ui.spinner.Spinner
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/** Consent-first voice enrollment, preparation, preview, and revocation. */
@Composable
fun VoiceCloneScreen(
    onBack: () -> Unit,
    onOpenVoiceLab: (() -> Unit)? = null,
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
        onOpenVoiceLab = onOpenVoiceLab,
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
    onRevoke: () -> Unit,
    onOpenVoiceLab: (() -> Unit)? = null,
) {
    var leaveAfterDiscard by remember { mutableStateOf(false) }
    val motion = RikkaTheme.motion

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

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = if (showEnrollment) "Voice setup" else "Your voice",
                navigationIcon = RikkaIcons.ArrowLeft,
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
                (fadeIn(tween(motion.durationSlow)) +
                    scaleIn(initialScale = 0.985f)) togetherWith
                    (fadeOut(tween(motion.durationDefault)) +
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
                    onRevoke = onRevoke,
                    onOpenVoiceLab = onOpenVoiceLab,
                )
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

@Composable
private fun VoiceEnrollmentExperience(
    state: VoiceCloneState,
    meter: VoiceCaptureMeter,
    leaveAfterDiscard: Boolean,
    onStartCapture: () -> Unit,
    onFinishCapture: () -> Unit
) {
    val spacing = RikkaTheme.spacing
    val motion = RikkaTheme.motion
    val stage = when {
        state.stage == VoiceCloneStage.RECORDING -> EnrollmentUiStage.RECORDING
        state.stage == VoiceCloneStage.PREPARING -> EnrollmentUiStage.PREPARING
        state.error != null -> EnrollmentUiStage.RETRY
        else -> EnrollmentUiStage.CONSENT
    }
    // The orb has no error state — a red orb would be a second, quieter error
    // message competing with the retry copy. Failure tints the accent instead
    // and the orb falls back to idle breathing.
    val orbState = when (stage) {
        EnrollmentUiStage.CONSENT, EnrollmentUiStage.RETRY -> VoiceOrbState.Idle
        EnrollmentUiStage.RECORDING -> VoiceOrbState.Listening
        EnrollmentUiStage.PREPARING -> VoiceOrbState.Thinking
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
                    .triplexContentWidth()
                    .padding(horizontal = TriplexLayout.screenHorizontal)
                    .padding(top = spacing.md, bottom = 168.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) spacing.lg else spacing.xl)
            ) {
                TriplexReveal {
                    VoiceEnrollmentProgress(currentIndex = progressIndex)
                }

                VoiceOrb(
                    state = orbState,
                    // A lambda, not a value: mic level arrives far faster than
                    // this screen should recompose, so the orb samples it in
                    // the draw phase.
                    amplitude = { meter.amplitude },
                    size = sphereSize,
                    accent = if (stage == EnrollmentUiStage.RETRY) {
                        RikkaTheme.colors.destructive
                    } else {
                        RikkaTheme.colors.primary
                    },
                    label = when (stage) {
                        EnrollmentUiStage.RECORDING -> "Voice sphere reacting to microphone level"
                        EnrollmentUiStage.PREPARING -> "Voice profile preparation in progress"
                        EnrollmentUiStage.RETRY -> "Voice capture needs another attempt"
                        EnrollmentUiStage.CONSENT -> "Voice sphere ready for enrollment"
                    }
                )

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(tween(motion.durationSlow)) +
                            slideInVertically(tween(motion.durationSlow)) { it / 8 }) togetherWith
                            (fadeOut(tween(motion.durationDefault)) +
                                slideOutVertically(tween(motion.durationDefault)) { -it / 10 })
                    },
                    label = "Voice enrollment instruction"
                ) { animatedStage ->
                    val animatedCopy = enrollmentCopy(
                        stage = animatedStage,
                        retryReason = state.error
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            text = animatedCopy.eyebrow,
                            variant = TextVariant.Small,
                            color = RikkaTheme.colors.primary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = animatedCopy.title,
                            variant = TextVariant.H2,
                            color = RikkaTheme.colors.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = animatedCopy.description,
                            color = RikkaTheme.colors.onMuted,
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
                                EnrollmentUiStage.RECORDING -> RikkaTheme.colors.secondary
                                EnrollmentUiStage.PREPARING -> RikkaTheme.colors.warning
                                EnrollmentUiStage.RETRY -> RikkaTheme.colors.destructive
                                EnrollmentUiStage.CONSENT -> RikkaTheme.colors.primary
                            }
                        )
                    }
                }

                AnimatedContent(
                    targetState = stage == EnrollmentUiStage.PREPARING,
                    transitionSpec = {
                        fadeIn(tween(motion.durationSlow)) togetherWith
                            fadeOut(tween(motion.durationDefault))
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
                            modifier = Modifier.padding(spacing.lg)
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
                            modifier = Modifier.padding(spacing.lg)
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
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = "ON-DEVICE QUALITY CHECK",
                variant = TextVariant.Small,
                color = RikkaTheme.colors.primary
            )
            Text(
                text = buildString {
                    append(String.format(Locale.ROOT, "%.1f sec captured", seconds))
                    snrDb?.let {
                        append(String.format(Locale.ROOT, " · %.0f dB signal-to-noise", it))
                    }
                },
                color = RikkaTheme.colors.onMuted
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
    val spacing = RikkaTheme.spacing
    val background = RikkaTheme.colors.background
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
            .padding(horizontal = TriplexLayout.screenHorizontal)
            .padding(top = spacing.xl, bottom = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        when (stage) {
            EnrollmentUiStage.CONSENT,
            EnrollmentUiStage.RETRY -> TriplexButton(
                text = if (stage == EnrollmentUiStage.RETRY) "Try voice capture again" else "Start reading",
                onClick = onStartCapture,
                leadingIcon = RikkaIcons.Mic,
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
            variant = TextVariant.Small,
            color = RikkaTheme.colors.onMuted.copy(alpha = 0.78f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VoicePreparingFooter() {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spinner(label = "Preparing your voice")
            Spacer(Modifier.width(spacing.md))
            Text(text = "Preparing your voice", variant = TextVariant.Large)
        }
    }
}

@Composable
private fun VoiceEnrollmentProgress(currentIndex: Int) {
    val spacing = RikkaTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
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
        complete -> RikkaTheme.colors.success
        active -> RikkaTheme.colors.primary
        else -> RikkaTheme.colors.border
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(RikkaTheme.motion.durationSlow),
        label = "Voice progress segment"
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm)
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
            variant = TextVariant.Small,
            color = if (active || complete) {
                RikkaTheme.colors.onBackground
            } else {
                RikkaTheme.colors.onMuted.copy(alpha = 0.58f)
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
    val spacing = RikkaTheme.spacing
    TriplexCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (recording) TriplexCardTone.ACCENT else TriplexCardTone.DEFAULT
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "READ THIS ALOUD",
                    variant = TextVariant.Small,
                    color = if (recording) {
                        RikkaTheme.colors.primary
                    } else {
                        RikkaTheme.colors.onMuted
                    }
                )
                if (recording) {
                    TriplexStatusPill(
                        text = "LISTENING",
                        tone = TriplexCardTone.ACCENT,
                        leadingColor = RikkaTheme.colors.secondary
                    )
                }
            }
            VoicePromptStatement(
                recording = recording,
                promptProgress = promptProgress,
                amplitude = meter.amplitude
            )
            AnimatedVisibility(visible = recording) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Progress(
                        progress = promptProgress.overallProgress,
                        trackColor = RikkaTheme.colors.onSurface.copy(alpha = 0.12f),
                        fillColor = RikkaTheme.colors.secondary,
                        height = 5.dp,
                        // The bar tracks the word the reader is on. Easing it in
                        // would put it behind the highlight it is meant to match.
                        animation = ProgressAnimation.None,
                        label = "Reading progress"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "FOLLOW THE HIGHLIGHT",
                            variant = TextVariant.Small,
                            color = RikkaTheme.colors.onSurface.copy(alpha = 0.68f)
                        )
                        Text(
                            text = "WORD ${promptProgress.activeWordIndex + 1} OF ${promptProgress.wordCount}",
                            variant = TextVariant.Small,
                            color = RikkaTheme.colors.onSurface.copy(alpha = 0.68f)
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
    val colors = RikkaTheme.colors

    if (!recording) {
        // The italic rides a span rather than a `style` override: the design
        // system's Text merges the variant style itself, so a TextStyle here
        // would be a second place this screen decides its own typography.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(CONSENT_STATEMENT) }
            },
            variant = TextVariant.Large,
            color = colors.onSurface
        )
        return
    }

    // The pill behind the active word has to be a transcript highlight rather
    // than a raw `background` span: the statement wraps across several lines,
    // and Compose would clip a plain background into hard rectangles at every
    // break. `transcriptHighlight` is composable, so it is resolved here and
    // reused inside the (non-composable) annotated-string builder below.
    val activeHighlight = transcriptHighlight(
        color = colors.secondary.copy(
            alpha = 0.16f + activePulse * 0.12f + amplitude.coerceIn(0f, 1f) * 0.10f
        )
    ).merge(
        SpanStyle(
            color = colors.onSurface,
            fontWeight = FontWeight.Bold
        )
    )

    val prompt = buildAnnotatedString {
        consentPromptWords.forEachIndexed { index, word ->
            val style = when {
                index < promptProgress.completedWordCount -> SpanStyle(
                    color = colors.secondary.copy(alpha = 0.80f),
                    fontWeight = FontWeight.Medium
                )
                index == promptProgress.activeWordIndex -> activeHighlight
                else -> SpanStyle(
                    color = colors.onSurface.copy(alpha = 0.43f)
                )
            }
            withStyle(style) { append(word) }
            if (index != consentPromptWords.lastIndex) append(' ')
        }
    }

    TranscriptText(text = prompt, color = colors.onSurface)
}

@Composable
private fun VoicePreparationCard() {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg)
        ) {
            Text(text = "Preparing your voice", variant = TextVariant.H3)
            PreparationRow(
                label = "Consent sample captured",
                status = "DONE",
                color = RikkaTheme.colors.success
            )
            PreparationRow(
                label = "Voice profile preparation",
                status = "IN PROGRESS",
                color = RikkaTheme.colors.primary
            )
            PreparationRow(
                label = "New-phrase preview",
                status = "NEXT",
                color = RikkaTheme.colors.border
            )
        }
    }
}

@Composable
private fun PreparationRow(label: String, status: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            variant = TextVariant.Small,
            color = RikkaTheme.colors.onSurface.copy(alpha = 0.68f)
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
    onRevoke: () -> Unit,
    onOpenVoiceLab: (() -> Unit)? = null,
) {
    val spacing = RikkaTheme.spacing
    val motion = RikkaTheme.motion
    val orbState = when (state.stage) {
        VoiceCloneStage.SYNTHESIZING -> VoiceOrbState.Thinking
        VoiceCloneStage.PLAYING -> VoiceOrbState.Speaking
        else -> VoiceOrbState.Idle
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 720.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .triplexContentWidth()
                .padding(horizontal = TriplexLayout.screenHorizontal)
                .padding(top = spacing.md, bottom = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) spacing.lg else spacing.xl)
        ) {
            VoiceEnrollmentProgress(currentIndex = 3)

            VoiceOrb(
                state = orbState,
                size = if (compact) 172.dp else 208.dp,
                label = when (state.stage) {
                    VoiceCloneStage.SYNTHESIZING -> "Creating a cloned voice preview"
                    VoiceCloneStage.PLAYING -> "Playing cloned voice preview"
                    else -> "Voice profile ready"
                }
            )

            AnimatedContent(
                targetState = state.stage,
                transitionSpec = {
                    fadeIn(tween(motion.durationSlow)) togetherWith
                        fadeOut(tween(motion.durationDefault))
                },
                label = "Voice ready headline"
            ) { stage ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
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
                            RikkaTheme.colors.warning
                        } else {
                            RikkaTheme.colors.success
                        }
                    )
                    Text(
                        text = when (stage) {
                            VoiceCloneStage.SYNTHESIZING -> "Creating your preview."
                            VoiceCloneStage.PLAYING -> "Listen—this is your new voice."
                            else -> "Your voice is ready."
                        },
                        variant = TextVariant.H2,
                        color = RikkaTheme.colors.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Try a sentence you never recorded, then compare it with your original sample.",
                        color = RikkaTheme.colors.onMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            TriplexCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg)
                ) {
                    Text(text = "Say something new", variant = TextVariant.H3)
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
                    Text(text = error, modifier = Modifier.padding(spacing.lg))
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
                    Text(text = message, modifier = Modifier.padding(spacing.lg))
                }
            }

            Separator(color = RikkaTheme.colors.border)

            onOpenVoiceLab?.let { openLab ->
                TriplexButton(
                    text = "Practice in voice lab",
                    onClick = openLab,
                    style = TriplexButtonStyle.SECONDARY,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TriplexButton(
                text = "Record a new sample",
                onClick = onStartCapture,
                style = TriplexButtonStyle.OUTLINE,
                leadingIcon = RikkaIcons.Mic,
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
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Synthesis placement", variant = TextVariant.H4)
                Spacer(Modifier.width(spacing.md))
                TriplexStatusPill(text = state.placement, tone = TriplexCardTone.ACCENT)
            }
            if (state.placementReason.isNotBlank()) {
                Text(
                    text = formatPlacementReason(state.placementReason),
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onSurface.copy(alpha = 0.72f)
                )
            }
            state.referenceSeconds?.let { seconds ->
                Text(
                    text = "Consent reference · ${seconds}s",
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onSurface.copy(alpha = 0.72f)
                )
            }
            state.captureSnrDb?.takeIf { state.error == null }?.let { snrDb ->
                Text(
                    text = String.format(
                        Locale.ROOT,
                        "On-device quality check · %.0f dB signal-to-noise",
                        snrDb
                    ),
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onSurface.copy(alpha = 0.72f)
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
