package dev.triplex.data.local

import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate

/**
 * The decisions `TelephonyController` makes from [AgentInboundConfig]
 * (reskin.md §2.3, seams 3–4).
 *
 * Pure functions, kept out of the SIP engine so they can be tested on the JVM:
 * the controller itself needs PJSIP, a native runtime and an Android `Context`,
 * so anything decided inside it is untestable by construction. The controller
 * calls straight into these — it does not keep a second copy of the rules.
 */
object AgentCallPolicy {

    /**
     * "Agent answers all calls", now a setting.
     *
     * Default-on lives in [AgentInboundConfig]; this is the only place the SIP
     * engine consults it, so both toggle positions are one test away.
     */
    fun shouldAutoAnswer(config: AgentInboundConfig): Boolean = config.autoAnswerAll

    /** The screening opening. Falls back to the shipped text if a user blanked it. */
    fun greeting(config: AgentInboundConfig): String =
        config.greetingText.trim().ifEmpty { AgentConfigDefaults.GREETING_TEXT }

    /**
     * The automation the user may hand this call to, or null when the id is
     * unknown or the user disabled it on the inbound setup screen.
     */
    fun automationFor(automationId: String, config: AgentInboundConfig): AutomationTemplate? {
        if (automationId !in config.enabledAutomationIds) return null
        return AutomationCatalog.byId(automationId)
    }
}
