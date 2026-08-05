package dev.triplex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.control.TurnController
import dev.triplex.telephony.sip.CallStateInfo
import dev.triplex.telephony.sip.TelephonyController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActiveCallViewModel @Inject constructor(
    private val telephonyController: TelephonyController,
    private val turnController: TurnController
) : ViewModel() {
    
    val callState: StateFlow<CallStateInfo> = telephonyController.callState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CallStateInfo.Idle)
    
    val turnState: StateFlow<TurnController.TurnState> = turnController.turnState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TurnController.TurnState.IDLE)
    
    val currentEpoch: StateFlow<Long> = MutableStateFlow(telephonyController.getCurrentEpoch())
    
    init {
        viewModelScope.launch {
            callState.collect { state ->
                turnController.handleCallState(state)
            }
        }
    }
    
    fun hangup() {
        telephonyController.hangup()
    }
    
    fun mute() {
        // TODO: Implement mute via audio pipeline
    }
    
    fun interrupt() {
        val epoch = telephonyController.getCurrentEpoch()
        turnController.injectInterruption("User requested", epoch)
    }
}
