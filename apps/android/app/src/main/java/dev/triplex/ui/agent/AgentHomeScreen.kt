package dev.triplex.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.components.TriplexTopBarAction
import dev.triplex.ui.components.TriplexTray
import dev.triplex.ui.components.TriplexTrayDivider
import dev.triplex.ui.components.TriplexTrayHeader
import dev.triplex.ui.components.TriplexTrayNavRow
import dev.triplex.ui.components.TriplexTrayRow
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.triplexContentWidth
import zed.rainxch.rikkaui.components.ui.call.VoiceOrb
import zed.rainxch.rikkaui.components.ui.call.VoiceOrbState
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.icon.RikkaIcons
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.spinner.Spinner
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Agent home (reskin.md §3.2).
 *
 * Two glass trays, not a stack of cards: a status tray (readiness + actions +
 * setup) and an activity tray (handled calls). Each tray is one backdrop
 * sampler — the same metaphor as the dialpad's single-surface pad below Full.
 */
@Composable
fun AgentHomeScreen(
    onOpenInbound: () -> Unit,
    onOpenOutbound: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenVoiceLab: () -> Unit,
    onOpenCallForward: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenRun: (String) -> Unit,
    viewModel: AgentHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sipState by viewModel.sipState.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val spacing = RikkaTheme.spacing

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = "Agent",
                actions = {
                    TriplexTopBarAction(text = "Demo", onClick = onOpenDemo)
                    TriplexTopBarAction(text = "My voice", onClick = onOpenVoice)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .triplexContentWidth(),
            contentPadding = PaddingValues(
                start = TriplexLayout.screenHorizontal,
                end = TriplexLayout.screenHorizontal,
                top = spacing.lg,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item(key = "status-tray") {
                AgentStatusTray(
                    sipState = sipState,
                    autoAnswerAll = state.autoAnswerAll,
                    clonedVoiceReady = state.clonedVoiceReady,
                    routingAttested = state.routingAttested,
                    hasSipCredentials = state.hasSipCredentials,
                    activeTask = state.activeTask,
                    error = state.error,
                    onDismissError = viewModel::dismissError,
                    onStopTask = { id -> viewModel.stopTask(id) },
                    onOpenInbound = onOpenInbound,
                    onOpenOutbound = onOpenOutbound,
                    onOpenCallForward = onOpenCallForward,
                    onOpenVoiceLab = onOpenVoiceLab,
                    onOpenVoice = onOpenVoice,
                    onOpenDemo = onOpenDemo,
                )
            }

            item(key = "activity-tray") {
                AgentActivityTray(
                    loading = state.loading && runs.isEmpty(),
                    runs = runs,
                    onOpenRun = onOpenRun,
                )
            }
        }
    }
}

/**
 * Readiness, capability actions, and setup — one accent-tinted glass object.
 */
@Composable
private fun AgentStatusTray(
    sipState: SipState,
    autoAnswerAll: Boolean,
    clonedVoiceReady: Boolean,
    routingAttested: Boolean,
    hasSipCredentials: Boolean,
    activeTask: TaskDefinition?,
    error: String?,
    onDismissError: () -> Unit,
    onStopTask: (String) -> Unit,
    onOpenInbound: () -> Unit,
    onOpenOutbound: () -> Unit,
    onOpenCallForward: () -> Unit,
    onOpenVoiceLab: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    val registered = sipState == SipState.READY || sipState == SipState.IN_CALL
    val lineReady = sipState == SipState.READY || sipState == SipState.IN_CALL

    TriplexTray(
        tone = TriplexCardTone.ACCENT,
        level = GlassLevel.Regular,
    ) {
        // ── Readiness ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                VoiceOrb(
                    state = if (sipState == SipState.IN_CALL) {
                        VoiceOrbState.Listening
                    } else {
                        VoiceOrbState.Idle
                    },
                    size = 76.dp,
                )
                Icon(
                    imageVector = RikkaIcons.Phone,
                    contentDescription = null,
                    tint = RikkaTheme.colors.onPrimary,
                    size = IconSize.Lg,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                TriplexStatusPill(text = sipLabel(sipState), tone = sipTone(sipState))
                Text(
                    text = readinessHeadline(
                        registered = registered,
                        autoAnswerAll = autoAnswerAll,
                        hasSipCredentials = hasSipCredentials,
                        clonedVoiceReady = clonedVoiceReady,
                    ),
                    variant = TextVariant.H3,
                )
                Text(
                    text = sipDetail(sipState),
                    color = RikkaTheme.colors.onMuted,
                )
                Text(
                    text = capabilityLadderLine(
                        clonedVoiceReady = clonedVoiceReady,
                        hasSipCredentials = hasSipCredentials,
                        registered = registered,
                        routingAttested = routingAttested,
                    ),
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.onMuted,
                )
            }
        }

        TriplexTrayDivider()

        // ── Capability actions ─────────────────────────────────────
        Column(
            modifier = Modifier.padding(
                horizontal = spacing.lg,
                vertical = spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TriplexButton(
                text = "Try your agent",
                onClick = onOpenVoiceLab,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!clonedVoiceReady) {
                TriplexButton(
                    text = "Set up my voice",
                    onClick = onOpenVoice,
                    modifier = Modifier.fillMaxWidth(),
                    style = TriplexButtonStyle.SECONDARY,
                )
            }
            if (!hasSipCredentials || !lineReady) {
                TriplexButton(
                    text = "Watch how it works",
                    onClick = onOpenDemo,
                    modifier = Modifier.fillMaxWidth(),
                    style = TriplexButtonStyle.OUTLINE,
                )
            }
            if (hasSipCredentials && !routingAttested) {
                TriplexButton(
                    text = "Forward my SIM",
                    onClick = onOpenCallForward,
                    modifier = Modifier.fillMaxWidth(),
                    style = TriplexButtonStyle.OUTLINE,
                )
            }
        }

        TriplexTrayDivider()

        // ── Setup destinations ─────────────────────────────────────
        TriplexTrayNavRow(
            title = "Incoming calls",
            description = "Answering, greeting, automations, quick replies",
            onClick = onOpenInbound,
        )
        TriplexTrayDivider()
        TriplexTrayNavRow(
            title = "Outgoing calls",
            description = "Tasks the agent places on your behalf",
            onClick = onOpenOutbound,
        )
        TriplexTrayDivider()
        TriplexTrayNavRow(
            title = "SIM call forwarding",
            description = "Route unanswered SIM rings to your Triplex line",
            onClick = onOpenCallForward,
        )

        activeTask?.let { task ->
            TriplexTrayDivider()
            ActiveTaskRow(task = task, onStop = { onStopTask(task.id) })
        }

        error?.let { message ->
            TriplexTrayDivider()
            TriplexTrayRow(onClick = onDismissError) {
                Text(
                    text = message,
                    color = RikkaTheme.colors.destructive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Handled-call history as one tray. Rows are embedded — no per-run glass.
 */
@Composable
private fun AgentActivityTray(
    loading: Boolean,
    runs: List<AgentRun>,
    onOpenRun: (String) -> Unit,
) {
    val spacing = RikkaTheme.spacing

    TriplexTray(level = GlassLevel.Regular) {
        TriplexTrayHeader(
            eyebrow = "Activity",
            title = "Calls the agent handled",
            description = "Stored on this phone only. Nothing is uploaded.",
        )

        when {
            loading -> {
                TriplexTrayDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Spinner()
                }
            }

            runs.isEmpty() -> {
                TriplexTrayDivider()
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

            else -> {
                runs.forEach { run ->
                    TriplexTrayDivider()
                    AgentRunRow(
                        run = run,
                        onClick = { onOpenRun(run.id) },
                        modifier = Modifier.fillMaxWidth(),
                        embedded = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTaskRow(
    task: TaskDefinition,
    onStop: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    TriplexTrayRow(
        contentPadding = PaddingValues(
            horizontal = spacing.lg,
            vertical = spacing.md,
        ),
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

/** Kept for call sites that still import a standalone active-task card. */
@Composable
internal fun ActiveTaskCard(
    task: TaskDefinition,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TriplexTray(modifier = modifier, tone = TriplexCardTone.SUCCESS) {
        ActiveTaskRow(task = task, onStop = onStop)
    }
}

private fun readinessHeadline(
    registered: Boolean,
    autoAnswerAll: Boolean,
    hasSipCredentials: Boolean,
    clonedVoiceReady: Boolean,
): String = when {
    registered && autoAnswerAll -> "The agent answers your calls"
    registered -> "The agent answers only when you hand a call to it"
    !hasSipCredentials && clonedVoiceReady -> "Voice ready — practice while the line catches up"
    !hasSipCredentials -> "Dialer ready — set up voice or try the lab"
    else -> "The agent cannot take live calls yet"
}

private fun capabilityLadderLine(
    clonedVoiceReady: Boolean,
    hasSipCredentials: Boolean,
    registered: Boolean,
    routingAttested: Boolean,
): String {
    val voice = if (clonedVoiceReady) "Voice ✓" else "Voice ·"
    val line = when {
        registered -> "Line ✓"
        hasSipCredentials -> "Line …"
        else -> "Line ·"
    }
    val routing = if (routingAttested) "Routing ✓" else "Routing ·"
    return "$voice  $line  $routing"
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
