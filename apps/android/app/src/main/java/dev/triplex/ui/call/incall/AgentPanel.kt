package dev.triplex.ui.call.incall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.triplex.ui.call.shared.TranscriptTimeline
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexChoiceChip
import dev.triplex.ui.components.TriplexStatusPill
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * What Triplex can do on the call that is already running (reskin.md §3.4).
 *
 * The panel is drawn on every call, including the SIM calls Triplex cannot hear.
 * On those it collapses to one honest sentence — and to a hand-off control only
 * where the hand-off can actually be performed — because "Triplex is not on this
 * call" is information the user needs, and a panel that vanished would leave
 * them guessing whether the agent was silently listening.
 *
 * Everything here is a callback. The panel neither knows nor cares which
 * telephony stack carries the leg.
 */
@Composable
fun AgentPanel(
    state: AgentPanelState,
    onAgentAction: (AgentActionId) -> Unit,
    onAutomation: (String) -> Unit,
    onQuickReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RikkaTheme.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        TriplexStatusPill(
            text = state.status,
            tone = if (state.agentHasMedia) TriplexCardTone.ACCENT else TriplexCardTone.DEFAULT,
        )

        state.takeoverNotice?.let { notice ->
            TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.WARNING) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }

        if (state.showTranscript) {
            TranscriptRegion(state)
        }

        if (state.quickReplies.isNotEmpty()) {
            QuickReplyRow(replies = state.quickReplies, onQuickReply = onQuickReply)
        }

        HandOffControl(state = state, onAgentAction = onAgentAction, onAutomation = onAutomation)

        state.action(AgentActionId.TAKE_BACK)?.let { takeBack ->
            AgentActionButton(action = takeBack, onAgentAction = onAgentAction)
        }
    }
}

@Composable
private fun ColumnScope.TranscriptRegion(state: AgentPanelState) {
    if (state.timeline.isEmpty()) {
        Text(
            text = "Listening for why they are calling\u2026",
            modifier = Modifier.fillMaxWidth(),
            color = RikkaTheme.colors.onMuted,
            textAlign = TextAlign.Center,
        )
        return
    }
    TranscriptTimeline(
        entries = state.timeline,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 280.dp),
    )
}

@Composable
private fun ColumnScope.QuickReplyRow(
    replies: List<String>,
    onQuickReply: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        replies.forEach { reply ->
            TriplexChoiceChip(
                label = reply,
                selected = false,
                onClick = { onQuickReply(reply) },
            )
        }
    }
}

/**
 * The hand-off control, in whichever of its two shapes this call needs.
 *
 * A call Triplex already carries is handed to a named automation, so the control
 * opens a picker and the choice is what actually dispatches. A SIM call is
 * handed over wholesale — there is no automation id to carry — so the control is
 * the action itself.
 */
@Composable
private fun ColumnScope.HandOffControl(
    state: AgentPanelState,
    onAgentAction: (AgentActionId) -> Unit,
    onAutomation: (String) -> Unit,
) {
    val handOff = state.action(AgentActionId.HAND_OFF) ?: return

    if (state.automations.isEmpty()) {
        AgentActionButton(action = handOff, onAgentAction = onAgentAction)
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    TriplexButton(
        text = if (expanded) "Hide automations" else handOff.label,
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        style = TriplexButtonStyle.GHOST,
        enabled = handOff.enabled,
    )
    handOff.reason?.takeIf { !handOff.enabled }?.let { DisabledReason(it) }

    AnimatedVisibility(visible = expanded && handOff.enabled) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
        ) {
            state.automations.forEach { automation ->
                TriplexButton(
                    text = automation.title,
                    onClick = { onAutomation(automation.id) },
                    modifier = Modifier.fillMaxWidth(),
                    style = TriplexButtonStyle.OUTLINE,
                )
            }
        }
    }
}

/** An agent control plus, when it cannot run, the sentence that says why. */
@Composable
private fun ColumnScope.AgentActionButton(
    action: AgentAction,
    onAgentAction: (AgentActionId) -> Unit,
) {
    TriplexButton(
        text = action.label,
        onClick = { onAgentAction(action.id) },
        modifier = Modifier.fillMaxWidth(),
        style = TriplexButtonStyle.SECONDARY,
        enabled = action.enabled,
    )
    action.reason?.takeIf { !action.enabled }?.let { DisabledReason(it) }
}

@Composable
private fun ColumnScope.DisabledReason(reason: String) {
    Text(
        text = reason,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Unavailable: $reason" },
        variant = TextVariant.Small,
        color = RikkaTheme.colors.onMuted,
    )
}
