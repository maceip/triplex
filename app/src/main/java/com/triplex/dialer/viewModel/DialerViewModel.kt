package com.triplex.dialer.viewModel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplex.dialer.Constants
import com.triplex.dialer.model.*
import com.triplex.dialer.service.CallRepository
import com.triplex.dialer.triplex.TriplexClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Main ViewModel for the dialer app.
 *
 * Bridges TriplexClient state into Compose UI state.
 * Handles dial input, call placement, mute/speaker toggles.
 */
class DialerViewModel(private val triplexClient: TriplexClient) : ViewModel() {

    private val _uiState = MutableStateFlow(DialerUiState(triplexReady = true))
    val uiState: StateFlow<DialerUiState> = _uiState

    val capabilities: StateFlow<TriplexCallCapabilities> = triplexClient.capabilities

    /** Settings state — exposed to SettingsScreen. */
    var echoDelayMs: Int = Constants.DEFAULT_ECHO_DELAY_MS
        private set
    var cloneProfiles: List<VoiceCloneProfile> = emptyList()
        private set
    var interruptionDeadlineMs: Long = Constants.INTERRUPTION_DEADLINE_MS.toLong()
        private set

    init {
        viewModelScope.launch {
            triplexClient.capabilities.collect { caps ->
                _uiState.value = _uiState.value.copy(triplexReady = true)
            }
        }
    }

    fun onDialDigit(digit: Char) {
        val current = _uiState.value.dialInput
        if (current.length < 20) {
            _uiState.value = _uiState.value.copy(dialInput = current + digit)
        }
    }

    fun onDialBackspace() {
        val current = _uiState.value.dialInput
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(dialInput = current.dropLast(1))
        }
    }

    fun placeCall(onNavigate: (String) -> Unit) {
        val number = _uiState.value.dialInput
        if (number.isBlank()) return

        viewModelScope.launch {
            val call = CallData(dialedNumber = number, isIncoming = false)
            CallRepository.registerCall(call)
            triplexClient.placeCall(number)

            _uiState.value = _uiState.value.copy(
                activeCalls = _uiState.value.activeCalls + call,
                dialInput = "",
                isInCall = true,
            )

            onNavigate(call.id)
        }
    }

    fun placeTriplexAgentCall(onNavigate: (String) -> Unit) {
        val number = _uiState.value.dialInput.ifBlank { "Triplex Hotel Booking" }

        viewModelScope.launch {
            val call = CallData(
                dialedNumber = number,
                isIncoming = false,
                isTriplexAgent = true,
            )
            CallRepository.registerCall(call)
            triplexClient.placeCall(number)

            _uiState.value = _uiState.value.copy(
                activeCalls = _uiState.value.activeCalls + call,
                dialInput = "",
                isInCall = true,
            )

            onNavigate(call.id)
        }
    }

    fun endCall(callId: String) {
        viewModelScope.launch {
            val call = CallRepository.resolveCall(callId)
            if (call != null) {
                triplexClient.endCall(call)
                CallRepository.removeCall(callId)

                // Move to call log
                val updatedCalls = _uiState.value.activeCalls.filter { it.id != callId }
                val updatedLog = _uiState.value.callLog + call

                _uiState.value = _uiState.value.copy(
                    activeCalls = updatedCalls,
                    callLog = updatedLog,
                    isInCall = false,
                )
            }
        }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMicMuted
        _uiState.value = _uiState.value.copy(isMicMuted = newMuted)

        // Forward to active calls
        for (call in _uiState.value.activeCalls) {
            triplexClient.setMuted(call, newMuted)
        }
    }

    fun toggleSpeaker() {
        _uiState.value = _uiState.value.copy(isSpeakerOn = !_uiState.value.isSpeakerOn)
        // Route audio to speaker/earpiece via AudioPipeline
    }
}
