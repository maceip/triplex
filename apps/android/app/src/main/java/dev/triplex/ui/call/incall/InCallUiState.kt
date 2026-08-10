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
import dev.triplex.ui.call.shared.CallActionTile

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
    val actions: List<CallActionTile> = emptyList(),
    /**
     * Audio outputs, when the platform offers a choice. Empty means the picker
     * is not drawn at all — there is nothing to pick between.
     */
    val audioEndpoints: List<AudioEndpointUi> = emptyList(),
    val agent: AgentPanelState = AgentPanelState(),
) {
    fun tile(id: CallActionId): CallActionTile? = actions.firstOrNull { it.id == id }
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
 * An agent control plus, when it cannot run, the sentence that says why.
 *
 * Never removed, only disabled — same rule the incoming sheet follows
 * (reskin.md §3.3, §5).
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

private fun buildTiles(session: CallSession): List<CallActionTile> {
    val capabilities = session.capabilities
    return buildList {
        // Mute and speaker ride on canMute for the same reason the repository
        // routes ToggleSpeaker through it: both are device-audio controls, and a
        // leg this phone carries no audio for has neither.
        if (capabilities.canMute) {
            add(
                CallActionTile(
                    id = CallActionId.MUTE,
                    label = if (session.isMuted) "Unmute" else "Mute",
                    active = session.isMuted,
                )
            )
            add(
                CallActionTile(
                    id = CallActionId.SPEAKER,
                    label = "Speaker",
                    active = session.isSpeakerOn,
                )
            )
        }
        if (capabilities.canHold) {
            add(
                CallActionTile(
                    id = CallActionId.HOLD,
                    label = if (session.phase == CallPhase.HOLDING) "Resume" else "Hold",
                    active = session.phase == CallPhase.HOLDING,
                )
            )
        }
        if (capabilities.canAddCall) {
            add(CallActionTile(id = CallActionId.ADD_CALL, label = "Add call"))
        }
        if (capabilities.canSwitch) {
            add(CallActionTile(id = CallActionId.SWITCH, label = "Switch"))
        }
        if (capabilities.canMerge) {
            add(CallActionTile(id = CallActionId.MERGE, label = "Merge"))
        }
        if (capabilities.canSwap) {
            add(CallActionTile(id = CallActionId.SWAP, label = "Swap"))
        }
        if (capabilities.canSendDtmf) {
            add(CallActionTile(id = CallActionId.KEYPAD, label = "Keypad"))
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

    val handOff = if (sip) {
        // The SIP hand-off is a hand-off *to an automation*: the engine needs an
        // id to look a template up, and one without it stops at "Automation
        // could not start on this call". So the precondition is that the user
        // has switched at least one automation on.
        AgentAction(
            id = AgentActionId.HAND_OFF,
            label = "Hand to an automation",
            enabled = capabilities.agentHasMedia && automations.isNotEmpty(),
            reason = when {
                !capabilities.agentHasMedia -> "Triplex is not on this call's audio."
                automations.isEmpty() ->
                    "Turn on an automation in Triplex settings before handing a call to it."

                else -> null
            },
        )
    } else if (session.phase == CallPhase.RINGING) {
        // Not reachable from this surface today — the shell routes a ringing
        // call to the sheet — but the projection stays total so the surface
        // cannot silently mis-describe a call it is handed.
        AgentAction(
            id = AgentActionId.HAND_OFF,
            label = "Send to Triplex",
            enabled = capabilities.canDeflectToAgent,
            reason = if (capabilities.canDeflectToAgent) {
                null
            } else {
                "Sending a SIM call to Triplex needs carrier deflection and an " +
                    "agent number set up on this device."
            },
        )
    } else {
        // Deflection is ringing-only, so a connected SIM call is handed over by
        // dialling Triplex as a second leg and conferencing it in. What that
        // needs is the ability to place that second leg; whether the carrier
        // then allows the merge is reported on the call, not guessed here.
        AgentAction(
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

    val takeBack = AgentAction(
        id = AgentActionId.TAKE_BACK,
        label = "Take the call back",
        enabled = capabilities.agentHasMedia,
        reason = if (capabilities.agentHasMedia) {
            null
        } else {
            "A SIM call handed to Triplex runs on your carrier's side of the " +
                "line, so this phone cannot pull it back."
        },
    )

    return listOf(handOff, takeBack)
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
