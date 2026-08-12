package dev.triplex.ui.call.incall

import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AudioEndpointUi
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate
import dev.triplex.ui.call.shared.AGENT_ONLY_AUDIO_NOTICE
import dev.triplex.ui.call.shared.CallActionId
import dev.triplex.ui.call.shared.CallAction

/**
 * Everything the in-call surface draws, derived from one [CallSession].
 *
 * Same shape and same reasoning as `IncomingCallUiState`: the surface is a
 * projection of call state rather than a destination, and the derivation is a
 * pure function so a JVM test can assert the whole thing — which tiles exist,
 * which agent controls are dead and the sentence that explains each one —
 * without Compose (reskin.md §3.4).
 *
 * The split between [actions] and [agent] follows the two questions the surface
 * answers. The tiles are *this phone's* controls over the leg; the panel is what
 * Triplex can do on it. A SIM call has plenty of the first and almost none of
 * the second, and saying so plainly is the point.
 */
data class InCallUiState(
    val visible: Boolean = false,
    val sessionId: String? = null,
    val displayName: String = "",
    val number: String = "",
    /** The stack headline, e.g. "Connected" or "On hold". */
    val statusLabel: String = "",
    /** Transfer/automation progress, when a stack has something to say. */
    val statusMessage: String = "",
    /** When the leg connected, for the elapsed clock. Null means no clock. */
    val connectedAtMs: Long? = null,
    val actions: List<CallAction> = emptyList(),
    /**
     * Audio outputs, when the platform offers a choice. Empty means the picker
     * is not drawn at all — there is nothing to pick between.
     */
    val audioEndpoints: List<AudioEndpointUi> = emptyList(),
    val agent: AgentPanelState = AgentPanelState(),
) {
    fun tile(id: CallActionId): CallAction? = actions.firstOrNull { it.id == id }
}

/**
 * The agent half of the surface (reskin.md §3.4).
 *
 * Present on every call, including the ones Triplex cannot touch. That is
 * deliberate: "Triplex is not on this call — your carrier owns its audio" is
 * information the user needs, and a panel that disappeared on SIM calls would
 * leave them guessing.
 */
data class AgentPanelState(
    val status: String = "",
    /** True only when Triplex holds the call's media and can speak on it. */
    val agentHasMedia: Boolean = false,
    val timeline: List<TimelineEntry> = emptyList(),
    val quickReplies: List<String> = emptyList(),
    val automations: List<AgentAutomationOption> = emptyList(),
    val actions: List<AgentAction> = emptyList(),
    /** Non-null means: say this out loud, do not imply a two-way call. */
    val takeoverNotice: String? = null,
) {
    /** Nothing to transcribe on a leg the agent cannot hear. */
    val showTranscript: Boolean get() = agentHasMedia

    fun action(id: AgentActionId): AgentAction? = actions.firstOrNull { it.id == id }
}

enum class AgentActionId {
    HAND_OFF,
    TAKE_BACK,
}

/**
 * An agent control plus, when it cannot run *yet*, the sentence that says why.
 *
 * The distinction is the same one the incoming sheet draws (reskin.md §3.3, §5).
 * A hand-off blocked by something about this moment — a call that cannot take a
 * second line right now — is disabled and explains itself, because it will work
 * on the next call and the user should know it is there. A hand-off that is
 * impossible on this call's stack, with nothing the user could do about it, is
 * left out of [AgentPanelState.actions] altogether.
 */
data class AgentAction(
    val id: AgentActionId,
    val label: String,
    val enabled: Boolean,
    val reason: String? = null,
)

data class AgentAutomationOption(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * Projects a session onto the in-call surface.
 *
 * @param session the primary call, or null when there is none.
 * @param config the user's inbound agent settings; it owns the reply chips and
 *   which automations may take a live call.
 * @param templates the inbound automation catalog.
 */
fun inCallUiState(
    session: CallSession?,
    config: AgentInboundConfig,
    templates: List<AutomationTemplate> = AutomationCatalog.inbound,
): InCallUiState {
    if (session == null || !session.isInCallSurface()) return InCallUiState()

    val capabilities = session.capabilities

    return InCallUiState(
        visible = true,
        sessionId = session.id,
        displayName = session.party.displayName.ifBlank {
            session.party.number.ifBlank { "Unknown caller" }
        },
        number = session.party.number,
        statusLabel = session.statusLabel,
        statusMessage = session.statusMessage,
        connectedAtMs = session.connectedAtMs?.takeIf { it > 0L },
        actions = buildTiles(session),
        audioEndpoints = capabilities.audioEndpoints,
        agent = agentPanelState(session, config, templates),
    )
}

/** Ringing and screening belong to the incoming sheet; everything later is ours. */
private fun CallSession.isInCallSurface(): Boolean =
    phase != CallPhase.RINGING && phase != CallPhase.SCREENING

private fun buildTiles(session: CallSession): List<CallAction> {
    val capabilities = session.capabilities
    return buildList {
        // Mute and speaker ride on canMute for the same reason the repository
        // routes ToggleSpeaker through it: both are device-audio controls, and a
        // leg this phone carries no audio for has neither.
        if (capabilities.canMute) {
            add(
                CallAction(
                    id = CallActionId.MUTE,
                    label = if (session.isMuted) "Unmute" else "Mute",
                    active = session.isMuted,
                )
            )
            add(
                CallAction(
                    id = CallActionId.SPEAKER,
                    label = "Speaker",
                    active = session.isSpeakerOn,
                )
            )
        }
        if (capabilities.canHold) {
            add(
                CallAction(
                    id = CallActionId.HOLD,
                    label = if (session.phase == CallPhase.HOLDING) "Resume" else "Hold",
                    active = session.phase == CallPhase.HOLDING,
                )
            )
        }
        if (capabilities.canAddCall) {
            add(CallAction(id = CallActionId.ADD_CALL, label = "Add call"))
        }
        if (capabilities.canSwitch) {
            add(CallAction(id = CallActionId.SWITCH, label = "Switch"))
        }
        if (capabilities.canMerge) {
            add(CallAction(id = CallActionId.MERGE, label = "Merge"))
        }
        if (capabilities.canSwap) {
            add(CallAction(id = CallActionId.SWAP, label = "Swap"))
        }
        if (capabilities.canSendDtmf) {
            add(CallAction(id = CallActionId.KEYPAD, label = "Keypad"))
        }
    }
}

private fun agentPanelState(
    session: CallSession,
    config: AgentInboundConfig,
    templates: List<AutomationTemplate>,
): AgentPanelState {
    val capabilities = session.capabilities
    val takeover = session.stack == CallStack.SIP && session.agentMode == AgentMode.HANDOFF
    val automations = templates
        .filter { it.id in config.enabledAutomationIds }
        .map { AgentAutomationOption(it.id, it.title, it.description) }

    return AgentPanelState(
        status = agentStatus(session, takeover),
        agentHasMedia = capabilities.agentHasMedia,
        timeline = session.timeline,
        quickReplies = if (capabilities.agentHasMedia) config.quickReplies else emptyList(),
        automations = if (capabilities.agentHasMedia) automations else emptyList(),
        actions = buildAgentActions(session, automations),
        takeoverNotice = if (takeover) AGENT_ONLY_AUDIO_NOTICE else null,
    )
}

private fun agentStatus(session: CallSession, takeover: Boolean): String = when {
    takeover -> "You took this call — Triplex is still the only voice on it"
    session.statusMessage.isNotBlank() -> session.statusMessage
    session.agentMode == AgentMode.AUTOMATION -> "An automation is running this call"
    session.agentMode == AgentMode.SCREENING -> "Triplex is screening this call"
    session.capabilities.agentHasMedia -> "Triplex is on this call"
    else -> "Triplex is not on this call — your carrier owns its audio"
}

private fun buildAgentActions(
    session: CallSession,
    automations: List<AgentAutomationOption>,
): List<AgentAction> {
    val capabilities = session.capabilities
    val sip = session.stack == CallStack.SIP

    val handOff = when {
        // The SIP hand-off is a hand-off *to an automation*: the engine needs an
        // id to look a template up, and one without it stops at "Automation
        // could not start on this call". With no automation switched on there is
        // nothing to hand the call to, so the control is not drawn — an enabled
        // picker with an empty list and a disabled button labelled "Hand to an
        // automation" both promise a hand-off this call cannot perform.
        sip -> if (capabilities.agentHasMedia && automations.isNotEmpty()) {
            AgentAction(
                id = AgentActionId.HAND_OFF,
                label = "Hand to an automation",
                enabled = true,
            )
        } else {
            null
        }

        // Not reachable from this surface today — the shell routes a ringing
        // call to the sheet — but the projection stays total so the surface
        // cannot silently mis-describe a call it is handed. Same rule as the
        // sheet's own SEND_TO_AGENT: offered only when deflection is available.
        session.phase == CallPhase.RINGING -> if (capabilities.canDeflectToAgent) {
            AgentAction(
                id = AgentActionId.HAND_OFF,
                label = "Send to Triplex",
                enabled = true,
            )
        } else {
            null
        }

        // Deflection is ringing-only, so a connected SIM call is handed over by
        // dialling Triplex as a second leg and conferencing it in. Two different
        // preconditions, and they fail differently. No agent number configured
        // means this build can never do it, so the control is absent. A call
        // that cannot take a second line right now is a passing condition — hold
        // the call, or wait for the conference to clear — so that one is drawn
        // disabled with the sentence that says so.
        !capabilities.canConferenceAgent -> null

        else -> AgentAction(
            id = AgentActionId.HAND_OFF,
            label = "Add Triplex to this call",
            enabled = capabilities.canAddCall,
            reason = if (capabilities.canAddCall) {
                null
            } else {
                "This call cannot take a second line right now."
            },
        )
    }

    // Taking a call back means interrupting the agent mid-sentence, which is
    // only meaningful where the agent has a voice on the call. A SIM leg handed
    // to Triplex runs on the carrier's side of the line and this phone has no
    // recall to offer, so there is no control rather than a dead one.
    val takeBack = if (capabilities.agentHasMedia) {
        AgentAction(
            id = AgentActionId.TAKE_BACK,
            label = "Take the call back",
            enabled = true,
        )
    } else {
        null
    }

    return listOfNotNull(handOff, takeBack)
}

/**
 * The call clock, `hh:mm:ss` past the hour and `mm:ss` below it.
 *
 * A pure function rather than a formatter inside the composable: the composable
 * owns the tick, this owns what the tick reads, and only one of those needs a
 * test.
 */
fun formatElapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val remainder = safe % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainder)
    } else {
        "%02d:%02d".format(minutes, remainder)
    }
}
