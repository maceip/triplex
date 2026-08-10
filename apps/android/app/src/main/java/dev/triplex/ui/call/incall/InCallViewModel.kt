package dev.triplex.ui.call.incall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallSessionRepository
import dev.triplex.ui.call.shared.CallActionId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the in-call surface.
 *
 * Holds no state of its own for the same reason the incoming sheet's view model
 * does not: the surface is the current [CallSessionRepository] session projected
 * through [inCallUiState], so process death cannot lose a live call and nothing
 * has to be restored. Every control routes a [CallIntent] back to the
 * repository, which is the only place that knows whether the call lives on the
 * SIM or on SIP (reskin.md §3.4).
 */
@HiltViewModel
class InCallViewModel @Inject constructor(
    private val calls: CallSessionRepository,
    agentConfig: AgentConfigRepository,
) : ViewModel() {

    val state: StateFlow<InCallUiState> =
        combine(calls.session, agentConfig.inbound) { session, config ->
            inCallUiState(session, config)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = inCallUiState(calls.session.value, AgentInboundConfig()),
        )

    /**
     * Runs a grid tile.
     *
     * A tile the projection did not produce is dropped: the capability behind it
     * is absent, so dispatching would be a no-op at best and a surprise at
     * worst.
     */
    fun onAction(id: CallActionId) {
        if (state.value.tile(id) == null) return
        val intent = when (id) {
            CallActionId.MUTE -> CallIntent.ToggleMute
            CallActionId.SPEAKER -> CallIntent.ToggleSpeaker
            CallActionId.HOLD -> CallIntent.ToggleHold
            CallActionId.ADD_CALL -> CallIntent.AddCall
            CallActionId.SWITCH -> CallIntent.SwitchCall
            CallActionId.MERGE -> CallIntent.MergeCalls
            CallActionId.SWAP -> CallIntent.SwapConference
            // The keypad is a disclosure, not a call operation: the screen opens
            // the pad and its keys arrive here as onDtmf.
            CallActionId.KEYPAD -> return
        }
        dispatch(intent)
    }

    fun onHangUp() = dispatch(CallIntent.HangUp)

    fun onDtmf(digit: Char) {
        if (state.value.tile(CallActionId.KEYPAD) == null) return
        dispatch(CallIntent.SendDtmf(digit))
    }

    fun onSelectAudioEndpoint(endpointId: String) {
        if (state.value.audioEndpoints.none { it.id == endpointId }) return
        dispatch(CallIntent.SelectAudioEndpoint(endpointId))
    }

    /**
     * Runs an agent control.
     *
     * A disabled control is dropped here as well as in the composable: its
     * reason text is the answer to that tap.
     *
     * [AgentActionId.HAND_OFF] carries no automation on the SIM stack because
     * there is nothing to carry — the call leaves this device entirely. On SIP
     * the id matters, so that path goes through [onAutomation] instead.
     */
    fun onAgentAction(id: AgentActionId) {
        val agent = state.value.agent
        if (agent.action(id)?.enabled != true) return
        when (id) {
            AgentActionId.HAND_OFF ->
                if (agent.automations.isEmpty()) dispatch(CallIntent.HandOffToAgent(null))

            AgentActionId.TAKE_BACK -> dispatch(CallIntent.TakeBackFromAgent)
        }
    }

    /** Hands the live call to an automation the user has enabled. */
    fun onAutomation(automationId: String) {
        val agent = state.value.agent
        if (agent.action(AgentActionId.HAND_OFF)?.enabled != true) return
        if (agent.automations.none { it.id == automationId }) return
        dispatch(CallIntent.HandOffToAgent(automationId))
    }

    /** Speaks one of the user's configured chips in their voice on the call. */
    fun onQuickReply(text: String) {
        if (text !in state.value.agent.quickReplies) return
        dispatch(CallIntent.SpeakReply(text))
    }

    private fun dispatch(intent: CallIntent) {
        calls.dispatch(intent, state.value.sessionId)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
