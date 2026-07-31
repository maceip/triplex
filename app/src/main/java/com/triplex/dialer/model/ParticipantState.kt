package com.triplex.dialer.model

/** Participant in a call — human caller or Triplex voice agent. */
data class ParticipantState(
    val participantId: String,
    val displayName: String = "",
    val isTriplexAgent: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioLevelDb: Float = -60.0f,
    val muteState: Boolean = false,
)
