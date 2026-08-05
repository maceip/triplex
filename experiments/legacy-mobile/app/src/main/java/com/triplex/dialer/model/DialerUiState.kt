package com.triplex.dialer.model

/** Top-level UI state for the dialer application. */
data class DialerUiState(
    val activeCalls: List<CallData> = emptyList(),
    val callLog: List<CallData> = emptyList(),
    val dialInput: String = "",
    val isInCall: Boolean = false,
    val isMicMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val hasNetwork: Boolean = true,
    val triplexReady: Boolean = false,
    val error: String? = null,
)
