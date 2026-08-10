package dev.triplex.ui.call.incoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the incoming-call sheet.
 *
 * It holds no state of its own: the sheet is the current [CallSessionRepository]
 * session projected through [incomingCallUiState], so an app restart or a tab
 * switch cannot lose it and nothing has to be restored. Every control routes a
 * [CallIntent] back to the repository, which is the only place that knows
 * whether the call lives on the SIM or on SIP (reskin.md §3.3).
 */
@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    private val calls: CallSessionRepository,
    agentConfig: AgentConfigRepository,
) : ViewModel() {

    val state: StateFlow<IncomingCallUiState> =
        combine(calls.session, agentConfig.inbound) { session, config ->
            incomingCallUiState(session, config)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = incomingCallUiState(calls.session.value, AgentInboundConfig()),
        )

    /**
     * Runs a sheet control.
     *
     * A disabled action is dropped here as well as in the composable: the reason
     * text is the answer to that tap, and dispatching anyway would either do
     * nothing silently or do something the capability says is impossible.
     */
    fun onAction(id: IncomingCallActionId) {
        val current = state.value
        if (current.action(id)?.enabled != true) return
        val intent = when (id) {
            IncomingCallActionId.ANSWER -> CallIntent.Answer
            IncomingCallActionId.DECLINE -> CallIntent.Decline
            IncomingCallActionId.HANG_UP -> CallIntent.HangUp
            IncomingCallActionId.TEXT_REPLY -> CallIntent.DeclineWithMessage(QUICK_TEXT_REPLY)
            IncomingCallActionId.SEND_TO_AGENT -> CallIntent.HandOffToAgent(null)
        }
        dispatch(intent)
    }

    /** Speaks one of the user's configured chips in their voice on the call. */
    fun onQuickReply(text: String) {
        if (text !in state.value.quickReplies) return
        dispatch(CallIntent.SpeakReply(text))
    }

    /** Hands the live call to an automation the user has enabled. */
    fun onAutomation(automationId: String) {
        if (state.value.automations.none { it.id == automationId }) return
        dispatch(CallIntent.HandOffToAgent(automationId))
    }

    private fun dispatch(intent: CallIntent) {
        calls.dispatch(intent, state.value.sessionId)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
