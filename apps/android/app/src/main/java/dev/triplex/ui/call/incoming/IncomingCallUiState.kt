package dev.triplex.ui.call.incoming

import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate
import dev.triplex.ui.call.shared.AGENT_ONLY_AUDIO_NOTICE

/**
 * Everything the incoming sheet draws, derived from one [CallSession].
 *
 * The sheet is a projection of call state, not a screen the user navigated to:
 * it appears because a session is ringing or being screened and disappears when
 * that stops being true. Keeping the derivation in a pure function here means a
 * JVM test can assert the whole surface — including which controls are dead and
 * why — without Compose.
 */
data class IncomingCallUiState(
    val visible: Boolean = false,
    val sessionId: String? = null,
    val displayName: String = "",
    val number: String = "",
    /** The stack headline, e.g. "Ringing" or "The caller is on hold". */
    val statusLabel: String = "",
    /** What the agent is doing, or why it is doing nothing. */
    val agentStatus: String = "",
    /** True only when the agent holds the call's media and can speak on it. */
    val agentHasMedia: Boolean = false,
    val timeline: List<TimelineEntry> = emptyList(),
    val quickReplies: List<String> = emptyList(),
    val automations: List<IncomingAutomationOption> = emptyList(),
    val actions: List<IncomingCallAction> = emptyList(),
    /**
     * Set when the user answered a leg whose audio the phone does not carry.
     * Non-null means: say this out loud, do not imply a two-way call.
     */
    val takeoverNotice: String? = null,
) {
    /**
     * Transcript region versus telecom fallback region. A SIM call has no agent
     * media, so there is nothing to transcribe and the sheet shows actions there
     * instead (reskin.md §3.3).
     */
    val showTranscript: Boolean get() = agentHasMedia

    fun action(id: IncomingCallActionId): IncomingCallAction? = actions.firstOrNull { it.id == id }
}

enum class IncomingCallActionId {
    ANSWER,
    DECLINE,
    HANG_UP,
    TEXT_REPLY,
    SEND_TO_AGENT,
}

/**
 * A control on the sheet.
 *
 * A missing capability disables an action and gives it a [reason]; it never
 * removes it. A control that vanishes teaches the user nothing, and a control
 * that does nothing when tapped is worse — so every disabled action carries the
 * sentence explaining itself (reskin.md §3.3, §5).
 */
data class IncomingCallAction(
    val id: IncomingCallActionId,
    val label: String,
    val enabled: Boolean,
    val reason: String? = null,
)

data class IncomingAutomationOption(
    val id: String,
    val title: String,
    val description: String,
)

/** The canned SMS the sheet's "Reply" action sends. */
const val QUICK_TEXT_REPLY: String = "Can't talk right now."

/**
 * Projects a session onto the sheet.
 *
 * @param session the primary call, or null when there is none.
 * @param config the user's inbound agent settings; it owns the reply chips and
 *   which automations may take a live call.
 * @param templates the inbound automation catalog.
 */
fun incomingCallUiState(
    session: CallSession?,
    config: AgentInboundConfig,
    templates: List<AutomationTemplate> = AutomationCatalog.inbound,
): IncomingCallUiState {
    if (session == null || !session.isIncomingSurface()) return IncomingCallUiState()

    val capabilities = session.capabilities
    val takeover = session.stack == CallStack.SIP && session.agentMode == AgentMode.HANDOFF

    return IncomingCallUiState(
        visible = true,
        sessionId = session.id,
        displayName = session.party.displayName.ifBlank { "Unknown caller" },
        number = session.party.number,
        statusLabel = session.statusLabel,
        agentStatus = agentStatus(session, takeover),
        agentHasMedia = capabilities.agentHasMedia,
        timeline = session.timeline,
        quickReplies = if (capabilities.agentHasMedia) config.quickReplies else emptyList(),
        automations = templates
            .filter { it.id in config.enabledAutomationIds }
            .map { IncomingAutomationOption(it.id, it.title, it.description) },
        actions = buildActions(session, takeover),
        takeoverNotice = if (takeover) AGENT_ONLY_AUDIO_NOTICE else null,
    )
}

/**
 * Ringing and screening are the two phases the sheet owns. Everything later
 * belongs to the in-call surface.
 */
private fun CallSession.isIncomingSurface(): Boolean =
    phase == CallPhase.RINGING || phase == CallPhase.SCREENING

private fun agentStatus(session: CallSession, takeover: Boolean): String = when {
    takeover -> "You answered — Triplex still holds the line"
    session.statusMessage.isNotBlank() -> session.statusMessage
    session.agentMode == AgentMode.AUTOMATION -> "An automation is running this call"
    session.agentMode == AgentMode.SCREENING -> "Triplex is screening this call"
    session.capabilities.agentHasMedia -> "Triplex is on this call"
    // A SIM leg: be explicit that the agent is not listening to it.
    else -> "Triplex is not on this call — your carrier owns its audio"
}

private fun buildActions(
    session: CallSession,
    takeover: Boolean,
): List<IncomingCallAction> {
    val capabilities = session.capabilities
    val agentless = !capabilities.agentHasMedia

    return listOf(
        IncomingCallAction(
            id = IncomingCallActionId.ANSWER,
            label = "Answer",
            enabled = capabilities.canAnswer,
            reason = when {
                capabilities.canAnswer -> null
                takeover -> "You already took this call."
                else -> "This call can no longer be answered."
            },
        ),
        IncomingCallAction(
            id = IncomingCallActionId.DECLINE,
            label = "Decline",
            enabled = capabilities.canDecline,
            reason = if (capabilities.canDecline) null else "This call can no longer be declined.",
        ),
        IncomingCallAction(
            id = IncomingCallActionId.HANG_UP,
            label = "Hang up",
            enabled = true,
        ),
        IncomingCallAction(
            id = IncomingCallActionId.TEXT_REPLY,
            label = "Reply with text",
            enabled = capabilities.canTextReply,
            reason = when {
                capabilities.canTextReply -> null
                agentless -> "This call does not offer a text reply on your carrier or SIM."
                else -> "A screened call has no SMS line to reply on."
            },
        ),
        IncomingCallAction(
            id = IncomingCallActionId.SEND_TO_AGENT,
            label = "Send to agent",
            enabled = capabilities.canDeflectToAgent,
            reason = when {
                capabilities.canDeflectToAgent -> null
                // canDeflectToAgent folds both causes on a SIM leg: the carrier
                // may not support deflection, or no agent number is configured.
                agentless ->
                    "Sending a SIM call to Triplex needs carrier deflection and an " +
                        "agent number set up on this device."

                else -> "This call has already been handed over."
            },
        ),
    )
}
