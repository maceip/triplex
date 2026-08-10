package dev.triplex.domain.call

/**
 * The one call model every call surface renders (reskin.md §2.3).
 *
 * Telecom (SIM) calls and Plivo/SIP calls stay separate below this layer; the
 * UI sees exactly one shape and branches only on [CallCapabilities], never on
 * [CallStack], to decide what to draw.
 */
enum class CallStack { TELECOM, SIP }

enum class CallPhase { RINGING, DIALING, SCREENING, ACTIVE, HOLDING, ENDING, ENDED }

/** Who is driving the call. */
enum class AgentMode { NONE, SCREENING, AUTOMATION, HANDOFF }

enum class CallDirection { INCOMING, OUTGOING }

data class CallParty(
    val number: String,
    /** Resolved through the directory repository; falls back to the number. */
    val displayName: String,
    val contactId: Long? = null,
    val photoUri: String? = null,
)

data class AudioEndpointUi(
    val id: String,
    val name: String,
    val selected: Boolean,
)

data class CallCapabilities(
    val canAnswer: Boolean = false,
    val canDecline: Boolean = false,
    val canTextReply: Boolean = false,
    val canHold: Boolean = false,
    val canMute: Boolean = false,
    val canSendDtmf: Boolean = false,
    val canAddCall: Boolean = false,
    val canSwitch: Boolean = false,
    val canMerge: Boolean = false,
    val canSwap: Boolean = false,
    /** Telecom, pre-answer, carrier-dependent (`CAPABILITY_SUPPORT_DEFLECT`). */
    val canDeflectToAgent: Boolean = false,
    /**
     * True only on the SIP stack.
     *
     * A third-party default dialer gets call *state*, not call *audio*
     * (reskin.md §5): `InCallService` exposes no media stream, so the agent can
     * never listen or speak on a SIM-leg Telecom call. Every code path that
     * would give the agent a voice is gated on this flag.
     */
    val agentHasMedia: Boolean = false,
    val audioEndpoints: List<AudioEndpointUi> = emptyList(),
)

data class CallSession(
    val id: String,
    val stack: CallStack,
    val direction: CallDirection,
    val party: CallParty,
    val phase: CallPhase,
    val agentMode: AgentMode,
    val connectedAtMs: Long?,
    val capabilities: CallCapabilities,
    val timeline: List<TimelineEntry>,
    val otherCallCount: Int,
    /**
     * Platform status headline ("Ringing", "On hold", "Choose SIM", "The caller
     * is answering now"). Not derivable from [phase] alone — Telecom has more
     * states than the phase enum carries, and the screening statuses are
     * gateway strings — so the source that knows produces the label once.
     */
    val statusLabel: String,
    /** Transfer errors, transcription status, screening progress messages. */
    val statusMessage: String,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
)
