package dev.triplex.ui.call.incall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.call.shared.AudioRoutePicker
import dev.triplex.ui.call.shared.CallActionGrid
import dev.triplex.ui.call.shared.CallActionId
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.triplexContentWidth
import kotlinx.coroutines.delay
import zed.rainxch.rikkaui.components.ui.call.CallHeader
import zed.rainxch.rikkaui.components.ui.call.GlassDialpad
import zed.rainxch.rikkaui.components.ui.call.GlassDialpadDefaults
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The connected call (reskin.md §3.4).
 *
 * Like the incoming sheet, this is state rather than a destination: the shell
 * shows it because `CallSessionRepository` reports a session past ringing, so
 * there is nothing to navigate to and nothing to restore after process death.
 *
 * @param onAddCallDetour opens the dialler for the second leg. Adding a call is
 *   the one control that needs a destination — the platform hands us back a
 *   second session only once a number has been dialled — so the screen asks the
 *   host for it instead of pretending it can do it inline.
 */
@Composable
fun InCallScreenHost(
    onAddCallDetour: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    InCallScreen(
        state = state,
        onAction = { id ->
            viewModel.onAction(id)
            if (id == CallActionId.ADD_CALL && state.tile(CallActionId.ADD_CALL) != null) {
                onAddCallDetour()
            }
        },
        onHangUp = viewModel::onHangUp,
        onDtmf = viewModel::onDtmf,
        onSelectEndpoint = viewModel::onSelectAudioEndpoint,
        onQuickReply = viewModel::onQuickReply,
        onAutomation = viewModel::onAutomation,
        onAgentAction = viewModel::onAgentAction,
        modifier = modifier,
    )
}

@Composable
fun InCallScreen(
    state: InCallUiState,
    onAction: (CallActionId) -> Unit,
    onHangUp: () -> Unit,
    onDtmf: (Char) -> Unit,
    onSelectEndpoint: (String) -> Unit,
    onQuickReply: (String) -> Unit,
    onAutomation: (String) -> Unit,
    onAgentAction: (AgentActionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val spacing = RikkaTheme.spacing

    // The pad is a disclosure on top of the grid rather than a mode, so it
    // survives configuration change and closes when the call does.
    var showKeypad by rememberSaveable(state.sessionId) { mutableStateOf(false) }

    Column(
        // Capped after the scroll modifier, so the gesture still spans the whole
        // window while the controls stay a reachable column: a call action grid
        // stretched across an unfolded screen puts Mute and Hang up a hand apart.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .triplexContentWidth()
            .padding(horizontal = TriplexLayout.screenHorizontal, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallHeader(
            callerName = state.displayName,
            callerNumber = state.number,
            statusLine = state.statusLabel,
            modifier = Modifier.fillMaxWidth(),
            elapsed = rememberElapsedLabel(state.connectedAtMs),
        )

        CallActionGrid(
            tiles = state.actions,
            onAction = { id ->
                if (id == CallActionId.KEYPAD) showKeypad = !showKeypad else onAction(id)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        AudioRoutePicker(
            endpoints = state.audioEndpoints,
            onSelect = onSelectEndpoint,
            modifier = Modifier.fillMaxWidth(),
        )

        // The design system's pad with the tone key set: no letters, because
        // nobody spells a name into a live line, and no long-press `+`, because
        // `+` is not a tone.
        AnimatedVisibility(visible = showKeypad) {
            GlassDialpad(
                onKeyPress = onDtmf,
                keys = GlassDialpadDefaults.ToneKeys,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AgentPanel(
            state = state.agent,
            onAgentAction = onAgentAction,
            onAutomation = onAutomation,
            onQuickReply = onQuickReply,
            modifier = Modifier.fillMaxWidth(),
        )

        TriplexButton(
            text = "End call",
            onClick = onHangUp,
            modifier = Modifier.fillMaxWidth(),
            style = TriplexButtonStyle.DANGER,
        )
    }
}

/**
 * The call clock, ticking once a second.
 *
 * Returns null when the stack never reported a connect time — a SIP leg has
 * none, and counting from "now" would invent a duration the call never had.
 */
@Composable
private fun rememberElapsedLabel(connectedAtMs: Long?): String? {
    if (connectedAtMs == null) return null
    var seconds by remember(connectedAtMs) {
        mutableLongStateOf(elapsedSeconds(connectedAtMs))
    }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            seconds = elapsedSeconds(connectedAtMs)
            delay(TICK_MS)
        }
    }
    return formatElapsed(seconds)
}

private fun elapsedSeconds(connectedAtMs: Long): Long =
    (System.currentTimeMillis() - connectedAtMs) / 1_000L

private const val TICK_MS = 1_000L
