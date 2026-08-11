package dev.triplex.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.telephony.sip.TelephonyController.SipState
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.components.TriplexTopBarAction
import dev.triplex.ui.theme.TriplexLayout
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.call.VoiceOrb
import zed.rainxch.rikkaui.components.ui.call.VoiceOrbState
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.spinner.Spinner
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Agent home (reskin.md §3.2).
 *
 * Answers, in order: can the agent take a call right now, what is it doing, and
 * what did it do. Setup lives one tap away on the inbound/outbound screens
 * rather than inline, so this stays a status surface.
 */
@Composable
fun AgentHomeScreen(
    onOpenInbound: () -> Unit,
    onOpenOutbound: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenRun: (String) -> Unit,
    viewModel: AgentHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sipState by viewModel.sipState.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val spacing = RikkaTheme.spacing
    val motion = RikkaTheme.motion

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = "Agent",
                actions = { TriplexTopBarAction(text = "My voice", onClick = onOpenVoice) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = TriplexLayout.screenHorizontal,
                end = TriplexLayout.screenHorizontal,
                top = spacing.lg,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                // No entrance animation: this is the first thing on the screen
                // and the answer to "can the agent take a call right now", so
                // fading it in delays the one line the user came for.
                AgentReadinessCard(
                    sipState = sipState,
                    autoAnswerAll = state.autoAnswerAll,
                    clonedVoiceReady = state.clonedVoiceReady,
                )
            }

            item {
                SetupEntries(
                    onOpenInbound = onOpenInbound,
                    onOpenOutbound = onOpenOutbound,
                )
            }

            item {
                AnimatedVisibility(
                    visible = state.activeTask != null,
                    enter = fadeIn(tween(motion.durationSlow)) + expandVertically(),
                    exit = fadeOut(tween(motion.durationDefault)) + shrinkVertically(),
                ) {
                    state.activeTask?.let { task ->
                        ActiveTaskCard(task = task, onStop = { viewModel.stopTask(task.id) })
                    }
                }
            }

            state.error?.let { error ->
                item {
                    TriplexCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = TriplexCardTone.DANGER,
                        onClick = viewModel::dismissError,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                }
            }

            item {
                TriplexScreenHeader(
                    eyebrow = "ACTIVITY",
                    title = "Calls the agent handled",
                    description = "Stored on this phone only. Nothing is uploaded.",
                )
            }

            when {
                state.loading && runs.isEmpty() -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Spinner()
                    }
                }

                runs.isEmpty() -> item { EmptyRunsCard() }

                else -> items(runs, key = { it.id }) { run ->
                    AgentRunRow(
                        run = run,
                        onClick = { onOpenRun(run.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentReadinessCard(
    sipState: SipState,
    autoAnswerAll: Boolean,
    clonedVoiceReady: Boolean,
) {
    val spacing = RikkaTheme.spacing
    val registered = sipState == SipState.READY || sipState == SipState.IN_CALL
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Registered means the agent is sitting on the line waiting, which
            // is what Listening renders — a faster breath than Idle. The orb
            // has no content slot, so the glyph rides on top of it.
            Box(contentAlignment = Alignment.Center) {
                VoiceOrb(
                    state = if (registered) VoiceOrbState.Listening else VoiceOrbState.Idle,
                    size = 76.dp,
                )
                Icon(
                    imageVector = RikkaIcons.Phone,
                    contentDescription = null,
                    tint = RikkaTheme.colors.onPrimary,
                    // Was a loose 25dp; the design system sizes icons by step,
                    // and Lg (24dp) is the one that step lands on.
                    size = IconSize.Lg,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                TriplexStatusPill(text = sipLabel(sipState), tone = sipTone(sipState))
                Text(
                    text = if (registered && autoAnswerAll) {
                        "The agent answers your calls"
                    } else if (registered) {
                        "The agent answers only when you hand a call to it"
                    } else {
                        "The agent cannot take calls yet"
                    },
                    variant = TextVariant.H3,
                )
                Text(
                    text = sipDetail(sipState),
                    color = RikkaTheme.colors.onMuted,
                )
                Text(
                    text = if (clonedVoiceReady) {
                        "Your cloned voice is ready."
                    } else {
                        "Your cloned voice is not ready — the agent will not substitute one."
                    },
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onMuted,
                )
            }
        }
    }
}

@Composable
private fun SetupEntries(onOpenInbound: () -> Unit, onOpenOutbound: () -> Unit) {
    val spacing = RikkaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SetupEntryCard(
            title = "Incoming calls",
            description = "Answering, greeting, automations, quick replies",
            onClick = onOpenInbound,
        )
        SetupEntryCard(
            title = "Outgoing calls",
            description = "Tasks the agent places on your behalf",
            onClick = onOpenOutbound,
        )
    }
}

@Composable
private fun SetupEntryCard(title: String, description: String, onClick: () -> Unit) {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = title, variant = TextVariant.H4)
            Text(
                text = description,
                variant = TextVariant.Small,
                color = RikkaTheme.colors.onMuted,
            )
        }
    }
}

@Composable
internal fun ActiveTaskCard(
    task: TaskDefinition,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = modifier.fillMaxWidth(), tone = TriplexCardTone.SUCCESS) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                TriplexStatusPill(
                    text = "ACTIVE TASK",
                    tone = TriplexCardTone.SUCCESS,
                    leadingColor = RikkaTheme.colors.success,
                )
                Text(text = taskTypeLabel(task.task_type), variant = TextVariant.H4)
                Text(text = task.destination_number)
            }
            TriplexButton(text = "Stop", onClick = onStop, style = TriplexButtonStyle.DANGER)
        }
    }
}

@Composable
private fun EmptyRunsCard() {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(text = "No calls yet", variant = TextVariant.H3)
            Text(
                text = "When the agent answers or places a call, the transcript appears here.",
                color = RikkaTheme.colors.onMuted,
            )
        }
    }
}

private fun sipLabel(state: SipState): String = when (state) {
    SipState.UNCONFIGURED -> "NOT SET UP"
    SipState.NO_CREDENTIALS -> "NO CREDENTIALS"
    SipState.REGISTERING -> "CONNECTING"
    SipState.READY -> "READY"
    SipState.IN_CALL -> "ON A CALL"
    SipState.WAITING_FOR_NETWORK -> "WAITING FOR NETWORK"
    SipState.FAILED -> "NEEDS ATTENTION"
}

private fun sipTone(state: SipState): TriplexCardTone = when (state) {
    SipState.READY, SipState.IN_CALL -> TriplexCardTone.SUCCESS
    SipState.REGISTERING, SipState.WAITING_FOR_NETWORK -> TriplexCardTone.WARNING
    SipState.FAILED, SipState.NO_CREDENTIALS -> TriplexCardTone.DANGER
    SipState.UNCONFIGURED -> TriplexCardTone.DEFAULT
}

private fun sipDetail(state: SipState): String = when (state) {
    SipState.UNCONFIGURED -> "Finish enrollment to give the agent a line of its own"
    SipState.NO_CREDENTIALS -> "No SIP credentials on this device yet"
    SipState.REGISTERING -> "Registering with the Plivo endpoint"
    SipState.READY -> "Registered on the Triplex line"
    SipState.IN_CALL -> "A call is in progress"
    SipState.WAITING_FOR_NETWORK -> "Waiting for a usable network"
    SipState.FAILED -> "Registration failed; check credentials and connectivity"
}

internal fun taskTypeLabel(raw: String): String = raw
    .lowercase()
    .split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
