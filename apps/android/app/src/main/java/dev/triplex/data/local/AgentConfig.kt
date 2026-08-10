package dev.triplex.data.local

import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationVoicePolicy

/**
 * How the agent behaves on calls (reskin.md §2.4).
 *
 * Read by the inbound/outbound setup screens, by `TelephonyController`'s
 * auto-answer and greeting seams, and (Phase 4) by the incoming sheet's reply
 * chips. Everything here is device-local.
 */
data class AgentInboundConfig(
    /**
     * "Agent answers all calls". Default on: that is the product's promise, and
     * turning it off is an explicit choice, not a state the app can drift into.
     */
    val autoAnswerAll: Boolean = true,
    /** What the agent opens a screened call with. */
    val greetingText: String = AgentConfigDefaults.GREETING_TEXT,
    /** Which inbound automations the user may hand a live call to. */
    val enabledAutomationIds: Set<String> = AgentConfigDefaults.ENABLED_AUTOMATION_IDS,
    val voicePolicy: AutomationVoicePolicy = AutomationVoicePolicy.PRESET,
    /** Chips the incoming sheet offers; picking one makes the agent speak it. */
    val quickReplies: List<String> = AgentConfigDefaults.QUICK_REPLIES,
    /**
     * Take inbound calls on the phone instead of having the provider screen
     * them.
     *
     * Off by default, and that default is the honest one rather than a
     * cautious one: the SIP media path on this device works and the gateway
     * will bridge to it the moment this is set, but no inbound PSTN call has
     * ever completed that way — the provider account that would route one is
     * not switched on. Turning this on before that is true would connect a
     * caller to a phone nothing has ever reached and leave them listening to
     * silence, which is exactly the pretend-connection the routing rules
     * forbid. This is the flip; it should be made once a real call has been
     * seen to work, not before.
     */
    val bridgeCallsToPhone: Boolean = false,
)

data class AgentOutboundConfig(
    val defaultVoicePolicy: AutomationVoicePolicy = AutomationVoicePolicy.PRESET,
    /** Ask before an outbound task actually dials. Default on. */
    val confirmBeforeDial: Boolean = true,
)

data class AgentConfig(
    val inbound: AgentInboundConfig = AgentInboundConfig(),
    val outbound: AgentOutboundConfig = AgentOutboundConfig(),
)

object AgentConfigDefaults {
    /**
     * The greeting the screening agent used before it was configurable. This is
     * the single source of that text; `TelephonyController` reads it through the
     * config rather than holding its own copy.
     */
    const val GREETING_TEXT: String =
        "Hi. This is the Triplex screening assistant. Please say your name and why you are calling."

    /** Seeded from the catalog, so a new automation is on the moment it ships. */
    val ENABLED_AUTOMATION_IDS: Set<String> =
        AutomationCatalog.inbound.map { it.id }.toSet()

    val QUICK_REPLIES: List<String> = listOf(
        "I'll call you back shortly.",
        "Please send me a text with the details.",
        "I'm driving right now.",
        "Please take a message.",
    )
}
