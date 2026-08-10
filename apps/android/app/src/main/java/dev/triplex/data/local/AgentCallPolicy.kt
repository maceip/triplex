package dev.triplex.data.local

import dev.triplex.dialogue.DialogueBrief
import dev.triplex.dialogue.DialogueBudget
import dev.triplex.dialogue.DialoguePlan
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate
import dev.triplex.domain.model.TaskDefinition

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

    /**
     * The plan for a screened inbound call.
     *
     * @param automationId set when the user handed the call to an automation
     *   from the incoming sheet. An unknown or disabled id yields null — the
     *   caller must not silently get the generic screening agent when the user
     *   asked for something specific.
     */
    fun screeningPlan(
        config: AgentInboundConfig,
        automationId: String? = null,
    ): DialoguePlan? {
        val automation = automationId?.let { id ->
            automationFor(id, config) ?: return null
        }
        return DialogueBrief.screening(
            greeting = greeting(config),
            automation = automation?.let { template ->
                DialogueBrief.AutomationBrief(
                    opening = template.opening,
                    goal = template.goal,
                    collect = template.collect,
                )
            },
            budget = INBOUND_BUDGET,
        )
    }

    /**
     * The plan for an outbound task call.
     *
     * The gateway's task parameters are free-form, so the labels are mapped
     * here rather than in the dialogue module: what a `product` or a
     * `desired_outcome` means is this product's business, and the brief only
     * needs to know which of them the agent is allowed to say out loud.
     */
    fun outboundPlan(task: TaskDefinition): DialoguePlan {
        val params = task.task_params
        val product = params["product"].orEmpty().ifBlank { "an item" }
        val outcome = params["desired_outcome"].orEmpty().ifBlank { "a resolution" }
        return DialogueBrief.outboundTask(
            goal = "$product — the customer is asking for $outcome",
            facts = linkedMapOf(
                "order number" to params["order_number"].orEmpty(),
                "reason" to params["return_reason"].orEmpty(),
                "reference" to params["reference"].orEmpty(),
                "requested time" to params["requested_time"].orEmpty(),
            ),
            budget = OUTBOUND_BUDGET,
        )
    }

    /**
     * A screened caller is a stranger who rang the user's number. Six turns is
     * enough to find out who they are and what they want; past that the agent
     * is having a conversation the user did not ask for.
     */
    private val INBOUND_BUDGET = DialogueBudget(
        maxTurns = 6,
        maxDurationMs = 180_000L,
    )

    /**
     * Outbound the agent is working a support queue on the user's behalf, so
     * it gets longer: hold music, transfers, and "let me just check that" are
     * the normal shape of these calls.
     */
    private val OUTBOUND_BUDGET = DialogueBudget(
        maxTurns = 10,
        maxDurationMs = 420_000L,
    )
}
