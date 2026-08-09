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
    )

    val ExplainDelay = AutomationTemplate(
        id = "explain_delay",
        title = "Explain that I'm running late",
        description = "Give a polite update and ask whether anything should be passed along",
        direction = AutomationDirection.INBOUND,
        voicePolicy = AutomationVoicePolicy.CLONED,
    )

    val inbound = listOf(BookZoomMeeting, ExplainDelay)
    val outbound = listOf(ReturnSamsungItem)
}
