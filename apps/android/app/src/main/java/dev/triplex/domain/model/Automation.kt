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
    val acknowledgementPrefix: String = "",
    val acknowledgementSuffix: String = "",
) {
    /** Closing line once the caller has answered, wrapped around [summary]. */
    fun acknowledgement(summary: String): String =
        acknowledgementPrefix + summary + acknowledgementSuffix
}

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
        opening = "I can help arrange a Zoom meeting. Please tell me the day, time, " +
            "time zone, and email address you want to use.",
        acknowledgementPrefix = "Thank you. I recorded your proposed meeting details: ",
        acknowledgementSuffix = ". The account owner will confirm availability before " +
            "an invitation is sent.",
    )

    val ExplainDelay = AutomationTemplate(
        id = "explain_delay",
        title = "Explain that I'm running late",
        description = "Give a polite update and ask whether anything should be passed along",
        direction = AutomationDirection.INBOUND,
        voicePolicy = AutomationVoicePolicy.CLONED,
        opening = "I can take a message about the delay. Please tell me who the update " +
            "is for and anything else that should be passed along.",
        acknowledgementPrefix = "Thank you. I recorded this update: ",
        acknowledgementSuffix = ". I will pass it along.",
    )

    val inbound = listOf(BookZoomMeeting, ExplainDelay)
    val outbound = listOf(ReturnSamsungItem)

    fun byId(automationId: String): AutomationTemplate? =
        (inbound + outbound).firstOrNull { it.id == automationId }
}
