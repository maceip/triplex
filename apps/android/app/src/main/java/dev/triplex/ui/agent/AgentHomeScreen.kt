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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.components.TriplexTopBarAction
import dev.triplex.ui.theme.TriplexDesign
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.call.VoiceOrb
import zed.rainxch.rikkaui.components.ui.call.VoiceOrbState
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.spinner.Spinner
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
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion

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
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.large,
                bottom = spacing.xxLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            item {
                TriplexReveal {
                    AgentReadinessCard(
                        sipState = sipState,
                        autoAnswerAll = state.autoAnswerAll,
                        clonedVoiceReady = state.clonedVoiceReady,
                    )
                }
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
                    enter = fadeIn(tween(motion.standardMillis)) + expandVertically(),
                    exit = fadeOut(tween(motion.quickMillis)) + shrinkVertically(),
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
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium,
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
                            .padding(spacing.xxLarge),
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
    val spacing = TriplexDesign.spacing
    val registered = sipState == SipState.READY || sipState == SipState.IN_CALL
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xLarge),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
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
                verticalArrangement = Arrangement.spacedBy(spacing.small),
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
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = sipDetail(sipState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Text(
                    text = if (clonedVoiceReady) {
                        "Your cloned voice is ready."
                    } else {
                        "Your cloned voice is not ready — the agent will not substitute one."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun SetupEntries(onOpenInbound: () -> Unit, onOpenOutbound: () -> Unit) {
    val spacing = TriplexDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
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
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = modifier.fillMaxWidth(), tone = TriplexCardTone.SUCCESS) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
            ) {
                TriplexStatusPill(
                    text = "ACTIVE TASK",
                    tone = TriplexCardTone.SUCCESS,
                    leadingColor = TriplexDesign.colors.success,
                )
                Text(taskTypeLabel(task.task_type), style = MaterialTheme.typography.titleMedium)
                Text(task.destination_number, style = MaterialTheme.typography.bodyMedium)
            }
            TriplexButton(text = "Stop", onClick = onStop, style = TriplexButtonStyle.DANGER)
        }
    }
}

@Composable
private fun EmptyRunsCard() {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text("No calls yet", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "When the agent answers or places a call, the transcript appears here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
