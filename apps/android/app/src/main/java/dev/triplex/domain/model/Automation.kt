package dev.triplex.domain.model

enum class AutomationDirection {
    INBOUND,
    OUTBOUND,
}

enum class AutomationVoicePolicy {
    PRESET,
    CLONED,
}

data class AutomationTemplate(
    val id: String,
    val title: String,
    val description: String,
    val direction: AutomationDirection,
    val voicePolicy: AutomationVoicePolicy,
    /**
     * What the agent says when the user hands a live call to this automation.
     *
     * Held here rather than in a `when (automationId)` inside the SIP engine
     * (reskin.md §2.3, seam 4): the engine looks the template up and the strings
     * stay next to the automation they belong to. Empty for outbound templates,
     * whose opening is generated from the task parameters instead.
     */
    val opening: String = "",
    /**
     * What this automation is for, in the second person, handed to the
     * reasoner as part of the call's brief.
     *
     * This replaced a fixed acknowledgement line. The old shape was
     * opening → listen once → read the caller's words back, which cannot ask a
     * follow-up question and so cannot actually book anything; the caller who
     * said "Thursday" was told "I recorded: Thursday" and the call ended. The
     * automation is now a brief for a real conversation
     * (`dev.triplex.dialogue.DialogueBrief`), which is why the strings here
     * describe a goal instead of a script.
     */
    val goal: String = "",
    /** The details the automation exists to collect, in asking order. */
    val collect: List<String> = emptyList(),
)

object AutomationCatalog {
    val ReturnSamsungItem = AutomationTemplate(
        id = "item_return",
        title = "Help me return this item",
        description = "Call Samsung support and work toward a refund or replacement",
        direction = AutomationDirection.OUTBOUND,
        voicePolicy = AutomationVoicePolicy.CLONED,
    )

    val BookZoomMeeting = AutomationTemplate(
        id = "book_zoom",
        title = "Book a Zoom meeting with me",
        description = "Find a time that works and collect the invitation details",
        direction = AutomationDirection.INBOUND,
        voicePolicy = AutomationVoicePolicy.CLONED,
        opening = "I can help arrange a Zoom meeting. What day works for you?",
        goal = "arrange a Zoom meeting with the owner, who will confirm before any " +
            "invitation is sent.",
        collect = listOf("day", "time", "time zone", "email address"),
    )

    val ExplainDelay = AutomationTemplate(
        id = "explain_delay",
        title = "Explain that I'm running late",
        description = "Give a polite update and ask whether anything should be passed along",
        direction = AutomationDirection.INBOUND,
        voicePolicy = AutomationVoicePolicy.CLONED,
        opening = "I can take a message about the delay. Who is the update for?",
        goal = "take an accurate message about a delay and pass it on.",
        collect = listOf("who the update is for", "what should be passed along"),
    )

    val inbound = listOf(BookZoomMeeting, ExplainDelay)
    val outbound = listOf(ReturnSamsungItem)

    fun byId(automationId: String): AutomationTemplate? =
        (inbound + outbound).firstOrNull { it.id == automationId }
}
