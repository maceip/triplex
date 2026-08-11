package dev.triplex.dialogue

/**
 * Builds the [DialoguePlan] for a call from user configuration and, outbound,
 * from the task the user asked for.
 *
 * Kept pure and separate from the SIP engine for the same reason
 * `AgentCallPolicy` is: what the agent is instructed to be is a product
 * decision that should be readable, diff-able, and testable without a phone.
 * `:app` maps its own config types onto the parameters here; this module never
 * learns about DataStore or the gateway's task schema.
 */
object DialogueBrief {

    /**
     * The screening agent: it finds out who is calling and why, and commits to
     * nothing.
     *
     * @param greeting the user's configured opening, already resolved by
     *   `AgentCallPolicy.greeting`.
     * @param ownerName how the agent refers to the person it answers for, or
     *   null to stay generic. Never guessed — an agent that invents the name of
     *   the person it works for is worse than one that says "the owner".
     * @param automation the inbound automation the user handed this call to,
     *   when they picked one from the incoming sheet.
     */
    fun screening(
        greeting: String,
        ownerName: String? = null,
        automation: AutomationBrief? = null,
        budget: DialogueBudget = DialogueBudget(),
    ): DialoguePlan {
        val owner = ownerName?.takeIf(String::isNotBlank) ?: "the owner of this phone"
        val instructions = buildString {
            append("You are Triplex, a call-screening assistant answering the phone for $owner. ")
            append("Your job is to find out who is calling and what they need, and to take an accurate message. ")
            if (automation != null) {
                append("The owner has asked you to handle this specific request: ${automation.goal} ")
                if (automation.collect.isNotEmpty()) {
                    append("Collect these details, one question at a time: ")
                    append(automation.collect.joinToString(", "))
                    append(". ")
                }
            }
            append(GROUND_RULES)
            append(" You cannot agree to anything, accept charges, confirm appointments, or share ")
            append("any detail about $owner. If the caller asks for a decision, say the owner will follow up.")
        }
        return DialoguePlan(
            direction = CallDirection.INBOUND,
            opening = automation?.opening?.takeIf(String::isNotBlank) ?: greeting,
            systemInstructions = instructions,
            closingInstruction =
                " This is your last turn. Confirm back what you understood in one sentence and say goodbye.",
            closings = DialogueClosings(
                onBudgetReached =
                    "Thank you. I have the details and I will pass them along. Goodbye.",
                onReasonerLost =
                    "I am sorry, I am having trouble following the call. I will pass on that you " +
                        "called and someone will get back to you. Goodbye.",
                onCallerSilent =
                    "I could not hear anything further, so I will let you go. Goodbye.",
            ),
            budget = budget,
            // Screening: a one-off model hiccup should not hang up on a real
            // person who is mid-sentence. Nothing is being committed to, so
            // buying a turn with "could you say that again?" is safe.
            fallback = FallbackPolicy.FailSoft(
                listOf(
                    "Sorry, I did not quite catch that. Could you say it once more?",
                    "Apologies, the line is not clear. Could you repeat that for me?",
                ),
            ),
        )
    }

    /**
     * The agent placing a call on the user's behalf, working toward a stated
     * outcome.
     *
     * @param goal what the user is trying to achieve, in their words.
     * @param facts the specifics the agent may state — an order number, a
     *   reservation time. The agent states these and invents nothing else.
     */
    fun outboundTask(
        goal: String,
        facts: Map<String, String> = emptyMap(),
        budget: DialogueBudget = DialogueBudget(maxTurns = 8, maxDurationMs = 300_000L),
    ): DialoguePlan {
        val statedFacts = facts.entries
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { (label, value) -> "$label: $value" }
        val instructions = buildString {
            append("You are Triplex, calling on behalf of a customer who asked you to handle this: ")
            append(goal.trim().ifEmpty { "resolve their request" })
            append(". ")
            if (statedFacts.isNotEmpty()) {
                append("These are the only facts you may state: $statedFacts. ")
            }
            append(GROUND_RULES)
            append(" If you are asked for a detail you were not given, say you will have to check ")
            append("and ask them to note it. Work toward the outcome, but never accept a different ")
            append("one on the customer's behalf — report back what was offered instead.")
        }
        return DialoguePlan(
            direction = CallDirection.OUTBOUND,
            opening = outboundOpening(goal, facts),
            systemInstructions = instructions,
            closingInstruction =
                " This is your last turn. Summarize what was agreed in one sentence, thank them, and say goodbye.",
            closings = DialogueClosings(
                onBudgetReached =
                    "Thank you for your time. I have what I need and my customer will follow up. Goodbye.",
                onReasonerLost =
                    "I am sorry, I am having trouble on this line. I will have the customer call " +
                        "back directly rather than take up more of your time. Goodbye.",
                onCallerSilent =
                    "I think we may have lost the line. I will call back. Goodbye.",
            ),
            budget = budget,
            // Outbound the agent is transacting for the user. A reply generated
            // around a misheard turn can accept a store credit instead of a
            // refund, so the first failure ends the call honestly.
            fallback = FallbackPolicy.FailClosed,
        )
    }

    /**
     * The scripted first line of an outbound call.
     *
     * Deliberately not model-generated: the opening is the one utterance whose
     * exact wording the user should be able to read before the phone rings, and
     * it is spoken before any reasoner has been consulted.
     */
    fun outboundOpening(goal: String, facts: Map<String, String>): String = buildString {
        append("Hello. I am an assistant calling on behalf of a customer about ")
        append(goal.trim().ifEmpty { "a request" }.removeSuffix("."))
        append(".")
        facts.entries
            .filter { it.value.isNotBlank() }
            .take(MAX_SPOKEN_FACTS)
            .forEach { (label, value) -> append(" The $label is $value.") }
        append(" Can you help me with that?")
    }

    /** An inbound automation, expressed as the brief it really is. */
    data class AutomationBrief(
        val opening: String,
        val goal: String,
        /** The details the automation exists to collect, in asking order. */
        val collect: List<String> = emptyList(),
    )

    /**
     * The rules that apply to any Triplex call, spoken or not.
     *
     * "Never invent" is first because it is the one that matters: a screening
     * agent that guesses an address, or an outbound agent that guesses an order
     * number, does more damage than one that says it does not know.
     */
    private const val GROUND_RULES: String =
        "Never invent a fact, a name, a number, or a commitment — if you do not know, say so. " +
            "Reply in one or two short spoken sentences, as a person would on the phone. " +
            "No lists, no markdown, no stage directions. Ask one question at a time."

    /**
     * More than three specifics in an opening and the other party has stopped
     * listening before the question arrives.
     */
    private const val MAX_SPOKEN_FACTS = 3
}
