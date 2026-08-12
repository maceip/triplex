package dev.triplex.ui.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.journey.FloatingShapeBackdrop
import dev.triplex.ui.journey.JourneyHero
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import kotlinx.coroutines.delay
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

private data class DemoBeat(
    val title: String,
    val body: String,
    val mockLabel: String,
    val failure: Boolean = false,
    val tapX: Float = 0.72f,
    val tapY: Float = 0.62f,
)

private val DemoBeats = listOf(
    DemoBeat(
        title = "Clone your voice",
        body = "Read a short consent line. Triplex builds a local voice profile you can preview.",
        mockLabel = "Voice setup · Start reading",
        tapX = 0.5f,
        tapY = 0.78f,
    ),
    DemoBeat(
        title = "When capture fails",
        body = "Too short or too noisy — Triplex asks you to try again. No silent fake success.",
        mockLabel = "Voice setup · Try again",
        failure = true,
        tapX = 0.5f,
        tapY = 0.78f,
    ),
    DemoBeat(
        title = "Inbound agent",
        body = "On the Triplex line the agent answers, listens on-device, and speaks back.",
        mockLabel = "Incoming · Agent answering",
        tapX = 0.78f,
        tapY = 0.55f,
    ),
    DemoBeat(
        title = "When the line is not ready",
        body = "No SIP credentials yet — the agent stays honest and points you at setup.",
        mockLabel = "Agent · NO CREDENTIALS",
        failure = true,
        tapX = 0.5f,
        tapY = 0.7f,
    ),
    DemoBeat(
        title = "Outbound automation",
        body = "Describe a bounded task. Confirm. Triplex dials and runs it on the Triplex line.",
        mockLabel = "Outbound · Confirm dial",
        tapX = 0.7f,
        tapY = 0.8f,
    ),
    DemoBeat(
        title = "When you decline",
        body = "Confirm-before-dial means a denied prompt ends the run. The agent does not sneak a call.",
        mockLabel = "Outbound · Dial cancelled",
        failure = true,
        tapX = 0.28f,
        tapY = 0.8f,
    ),
)

/**
 * Hybrid cinematic trailer: mocked frames + loud tap cursors, then handoff into
 * real setup. Does not drive Telephony.
 */
@Composable
fun AgentDemoScreen(
    onBack: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenVoiceLab: () -> Unit,
    onOpenCallForward: () -> Unit,
    autoPlay: Boolean = true,
) {
    val spacing = RikkaTheme.spacing
    val motion = RikkaTheme.motion
    var index by remember { mutableIntStateOf(0) }
    val finished = index >= DemoBeats.size

    LaunchedEffect(autoPlay, finished) {
        if (!autoPlay || finished) return@LaunchedEffect
        while (index < DemoBeats.size) {
            delay(3_200)
            index += 1
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TriplexTopBar(
                title = if (finished) "You're set" else "How Triplex works",
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
                    .padding(horizontal = spacing.xl)
                    .padding(bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                if (finished) {
                    JourneyHero(
                        brand = "TRIPLEX",
                        title = "Pick a real next step.",
                        supporting = "The trailer was mocked. These open live setup on this phone.",
                        eyebrow = "HANDOFF",
                    )
                    TriplexButton(text = "Set up my voice", onClick = onOpenVoice)
                    TriplexButton(
                        text = "Practice voice lab",
                        onClick = onOpenVoiceLab,
                        style = TriplexButtonStyle.SECONDARY,
                    )
                    TriplexButton(
                        text = "Forward my SIM",
                        onClick = onOpenCallForward,
                        style = TriplexButtonStyle.OUTLINE,
                    )
                    TriplexButton(
                        text = "Replay trailer",
                        onClick = { index = 0 },
                        style = TriplexButtonStyle.GHOST,
                    )
                } else {
                    val beat = DemoBeats[index]
                    AnimatedContent(
                        targetState = beat,
                        transitionSpec = {
                            fadeIn(tween(motion.durationSlow)) togetherWith
                                fadeOut(tween(motion.durationDefault))
                        },
                        label = "demo-beat",
                    ) { current ->
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                            JourneyHero(
                                brand = "TRIPLEX",
                                title = current.title,
                                supporting = current.body,
                                eyebrow = if (current.failure) "FAILURE BEAT" else "WALKTHROUGH",
                            )
                            MockFrame(
                                label = current.mockLabel,
                                failure = current.failure,
                                tapX = current.tapX,
                                tapY = current.tapY,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TriplexButton(
                                    text = "Skip",
                                    onClick = { index = DemoBeats.size },
                                    style = TriplexButtonStyle.GHOST,
                                )
                                TriplexButton(
                                    text = if (index == DemoBeats.lastIndex) "Finish" else "Next",
                                    onClick = { index += 1 },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MockFrame(
    label: String,
    failure: Boolean,
    tapX: Float,
    tapY: Float,
) {
    val spacing = RikkaTheme.spacing
    val tap = remember { Animatable(0.4f) }
    LaunchedEffect(label) {
        while (true) {
            tap.animateTo(1f, tween(420))
            tap.animateTo(0.35f, tween(520))
        }
    }
    TriplexCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
        tone = if (failure) TriplexCardTone.DANGER else TriplexCardTone.ACCENT,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl)
                .size(width = 320.dp, height = 220.dp),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(text = "MOCK UI", variant = TextVariant.Small)
                Text(text = label, variant = TextVariant.H3)
                Text(
                    text = if (failure) "Shown so you know what a miss looks like."
                    else "Loud tap shows the next control.",
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onMuted,
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width * tapX, size.height * tapY)
                val radius = 28.dp.toPx() * tap.value
                drawCircle(
                    color = Color(0xFFFF3D71).copy(alpha = 0.35f * tap.value),
                    radius = radius * 1.8f,
                    center = center,
                )
                drawCircle(
                    color = Color(0xFFFF3D71),
                    radius = radius * 0.55f,
                    center = center,
                )
            }
        }
    }
}
