package dev.triplex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.nativebridge.agent.AgentBridge
import dev.triplex.nativebridge.agent.AgentState
import dev.triplex.telephony.sip.CallStateInfo
import dev.triplex.telephony.sip.TelephonyController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Live call state for the UI.
 *
 * Turn state is *observed* from the native agent session, never computed
 * here: the turn FSM has exactly one implementation, in Rust
 * (`agent-core::turn`). A second Kotlin state machine would drift from the
 * epoch the mixer actually gates on.
 */
@HiltViewModel
class ActiveCallViewModel @Inject constructor(
    private val telephonyController: TelephonyController,
    private val agentBridge: AgentBridge
) : ViewModel() {

    val callState: StateFlow<CallStateInfo> = telephonyController.callState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CallStateInfo.Idle)

    val turnState: StateFlow<AgentState> = agentBridge.status
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentState.STOPPED)

    /** Generation epoch from the native runtime — the single source of truth. */
    val currentEpoch: StateFlow<Long> = agentBridge.status
        .map { it.epoch }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun hangup() {
        telephonyController.hangup()
    }

    fun mute() {
        // TODO: route through the native mixer once takeover mode exists.
    }

    /** Manual barge-in: publishes an epoch bump in the native runtime. */
    fun interrupt() {
        agentBridge.interrupt("User requested")
    }
}
