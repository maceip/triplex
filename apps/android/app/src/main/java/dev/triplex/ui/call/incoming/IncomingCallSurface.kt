package dev.triplex.ui.call.incoming

import android.app.KeyguardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.call.shared.TranscriptTimeline
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexStatusPill
import zed.rainxch.rikkaui.components.ui.call.CallDecisionSlider
import zed.rainxch.rikkaui.components.ui.call.IncomingCallSheet
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The incoming call, hosted by the shell as an overlay.
 *
 * The surface is state, not a destination: it is on screen because
 * `CallSessionRepository` reports a ringing or screened session, so switching
 * tabs cannot navigate away from it and process death cannot lose it — the
 * session is rebuilt from the telephony stacks and the surface comes back with
 * it (reskin.md §3.3).
 *
 * Every control emits a `CallIntent`. Nothing here talks to a telephony stack.
 */
@Composable
fun IncomingCallSurfaceHost(
    modifier: Modifier = Modifier,
    viewModel: IncomingCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    IncomingCallSurface(
        state = state,
        onAction = viewModel::onAction,
        onQuickReply = viewModel::onQuickReply,
        onAutomation = viewModel::onAutomation,
        modifier = modifier,
    )
}

/**
 * Triplex's incoming call, drawn on the design system's [IncomingCallSheet].
 *
 * Named a surface rather than a sheet because the sheet is what it is *built
 * on*: this is the app's own filling — screening status, the transcript or its
 * carrier-gated substitute, the automation picker — and on a locked device it
 * swaps the sheet's two buttons for a [CallDecisionSlider].
 */
@Composable
fun IncomingCallSurface(
    state: IncomingCallUiState,
    onAction: (IncomingCallActionId) -> Unit,
    onQuickReply: (String) -> Unit,
    onAutomation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read once per call: a call that arrives on the lock screen keeps its
    // full-screen presentation even after the user unlocks to act on it, rather
    // than re-laying itself out under their thumb.
    val locked = remember(state.sessionId) {
        context.getSystemService<KeyguardManager>()?.isKeyguardLocked == true
    }

    val answer = state.action(IncomingCallActionId.ANSWER)
    val hangUp = state.action(IncomingCallActionId.HANG_UP)
    val decline = state.action(IncomingCallActionId.DECLINE)
    val sendToAgent = state.action(IncomingCallActionId.SEND_TO_AGENT)

    // The sheet's own decline-and-answer pair is the right control whenever the
    // call can simply be taken: two smoked-glass buttons, tinted success and
    // destructive. It is replaced only where the platform gets in the way — a
    // decision made from the lock screen, or an action the carrier has gated and
    // which therefore has to carry the reason it is dead.
    val lockedDecision = locked && answer?.enabled == true
    val gated = answer?.enabled != true || hangUp?.enabled != true

    // Number and stack status share the header's secondary line; the sheet has
    // one slot there and both are identifying context for the same caller.
    val callerDetail = remember(state.number, state.statusLabel) {
        listOf(state.number, state.statusLabel).filter(String::isNotBlank).joinToString(" · ")
    }

    IncomingCallSheet(
        callerName = state.displayName,
        onAnswer = { onAction(IncomingCallActionId.ANSWER) },
        onDecline = { onAction(IncomingCallActionId.HANG_UP) },
        answerLabel = answer?.label.orEmpty().ifBlank { "Answer" },
        declineLabel = hangUp?.label.orEmpty().ifBlank { "Decline" },
        modifier = if (locked) modifier.fillMaxSize() else modifier,
        callerDetail = callerDetail,
        quickReplies = state.quickReplies,
        onQuickReply = onQuickReply,
        visible = state.visible,
        content = {
            val spacing = RikkaTheme.spacing

            TriplexStatusPill(
                text = state.agentStatus,
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
            } else {
                TelecomFallbackRegion(state = state, onAction = onAction)
            }

            AutomationOverflow(state = state, onAutomation = onAutomation)
        },
        actions = if (lockedDecision || gated) {
            {
                if (lockedDecision) {
                    // The visible labels have to fit inside the tracks, so the
                    // platform's own wording rides the accessibility actions
                    // instead — that is where "Send to agent" is worth the extra
                    // words and the bottom track's "Agent" is not enough.
                    CallDecisionSlider(
                        onDecline = { onAction(IncomingCallActionId.DECLINE) },
                        onAnswer = { onAction(IncomingCallActionId.ANSWER) },
                        onTransfer = { onAction(IncomingCallActionId.SEND_TO_AGENT) },
                        declineActionLabel = decline?.label.orEmpty().ifBlank { "Decline call" },
                        answerActionLabel = answer.label.ifBlank { "Answer call" },
                        transferActionLabel = sendToAgent?.label.orEmpty()
                            .ifBlank { "Send to the Triplex agent" },
                    )
                } else {
                    PrimaryActions(state = state, onAction = onAction)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ColumnScope.TranscriptRegion(state: IncomingCallUiState) {
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
            .heightIn(min = 120.dp, max = 320.dp),
    )
}

/**
 * What replaces the transcript on a SIM call.
 *
 * A third-party dialer gets no media on a Telecom leg, so there is nothing to
 * transcribe. The honest substitute is the set of things the platform *does*
 * allow, each either live or visibly explained (reskin.md §5).
 */
@Composable
private fun ColumnScope.TelecomFallbackRegion(
    state: IncomingCallUiState,
    onAction: (IncomingCallActionId) -> Unit,
) {
    Text(
        text = "Triplex cannot hear this call, so there is no transcript. " +
            "These are the controls your carrier allows:",
        modifier = Modifier.fillMaxWidth(),
        color = RikkaTheme.colors.onMuted,
    )
    GatedAction(state.action(IncomingCallActionId.TEXT_REPLY), onAction)
    GatedAction(state.action(IncomingCallActionId.SEND_TO_AGENT), onAction)
}

@Composable
private fun ColumnScope.PrimaryActions(
    state: IncomingCallUiState,
    onAction: (IncomingCallActionId) -> Unit,
) {
    val answer = state.action(IncomingCallActionId.ANSWER)
    val hangUp = state.action(IncomingCallActionId.HANG_UP)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.md),
    ) {
        answer?.let {
            TriplexButton(
                text = it.label,
                onClick = { onAction(it.id) },
                modifier = Modifier.weight(1f),
                enabled = it.enabled,
            )
        }
        hangUp?.let {
            TriplexButton(
                text = it.label,
                onClick = { onAction(it.id) },
                modifier = Modifier.weight(1f),
                style = TriplexButtonStyle.DANGER,
                enabled = it.enabled,
            )
        }
    }
    // The reason a dead Answer is dead belongs next to it, not in a toast the
    // user has to catch.
    answer?.takeIf { !it.enabled }?.reason?.let { reason ->
        DisabledReason(reason)
    }
}

/** A control plus, when it cannot run, the sentence that says why. */
@Composable
private fun ColumnScope.GatedAction(
    action: IncomingCallAction?,
    onAction: (IncomingCallActionId) -> Unit,
) {
    if (action == null) return
    TriplexButton(
        text = action.label,
        onClick = { onAction(action.id) },
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

/**
 * The automation picker, folded away behind one control.
 *
 * Handing a live call to an automation is a deliberate act, so it sits one tap
 * deeper than answer and hang up rather than competing with them.
 */
@Composable
private fun ColumnScope.AutomationOverflow(
    state: IncomingCallUiState,
    onAutomation: (String) -> Unit,
) {
    if (state.automations.isEmpty()) return
    var expanded by rememberSaveable(state.sessionId) { mutableStateOf(false) }

    TriplexButton(
        text = if (expanded) "Hide automations" else "Hand to an automation",
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        style = TriplexButtonStyle.GHOST,
    )

    AnimatedVisibility(visible = expanded) {
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
