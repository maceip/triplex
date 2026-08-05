package com.triplex.dialer.model

/**
 * Unified session model for both incoming and outgoing AI-mediated calls.
 *
 * Outgoing: user asks AI to call a business → AI dials, speaks, resolves
 * Incoming: someone calls user → AI answers, resolves issue on user's behalf
 *
 * Both use the same state machine — the only difference is who initiated.
 */
enum class CallDirection {
    OUTGOING,   // User asked AI to make a call
    INCOMING,   // Someone called the user, AI answered
}

enum class TriplexCallPhase {
    INTENT_COLLECT,     // (outgoing) gather what user needs
    SCREENING,          // (incoming) AI answering, gathering context
    DIALING,            // (outgoing) placing the call
    SPEAKING,           // AI is talking
    LISTENING,          // AI is listening to the other party
    AWAITING_USER_INPUT, // AI hit a roadblock, needs user info
    SUCCESS,            // Goal achieved
    FAILURE,            // Couldn't complete
    CALLBACK_SCHEDULED, // Business was closed, will retry later
}

/** A single turn in the call transcript. */
data class TranscriptEntry(
    val speaker: TranscriptSpeaker,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class TranscriptSpeaker {
    USER,       // The app user (our customer)
    AI_AGENT,   // The Triplex AI speaking
    BUSINESS,   // The other party on the call
    SYSTEM,     // System status messages
}

/** Outcome of a completed AI-mediated call. */
data class SupportOutcome(
    val summary: String,
    val referenceNumber: String? = null,
    val nextAction: String? = null,
    val callbackTime: String? = null,
)

/** DTMF tone sequence to send during an outgoing call. */
data class DTMFSequence(
    val digits: String,
    val description: String = "",
)

/** Context the AI needs to handle an incoming call on the user's behalf. */
data class UserContext(
    val accountNumbers: List<String> = emptyList(),
    val knownIssues: List<String> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    val scheduledPayments: List<PaymentInfo> = emptyList(),
)

data class PaymentInfo(
    val amount: String,
    val dueDate: String,
    val status: String, // "pending", "overdue", "paid"
)

/** Full state for a single Triplex call session (incoming or outgoing). */
data class TriplexCallSession(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val direction: CallDirection = CallDirection.OUTGOING,
    val phase: TriplexCallPhase = TriplexCallPhase.INTENT_COLLECT,
    val transcript: List<TranscriptEntry> = emptyList(),
    val counterpartyName: String = "",    // business name (outgoing) or caller name (incoming)
    val issueDescription: String = "",
    val expectedOutcome: String = "",
    val outcome: SupportOutcome? = null,
    val durationSec: Int = 0,
    val pendingYank: YankRequest? = null,
    val pendingDTMF: DTMFSequence? = null,
    val userContext: UserContext? = null,  // for incoming calls — what AI knows about user
)
