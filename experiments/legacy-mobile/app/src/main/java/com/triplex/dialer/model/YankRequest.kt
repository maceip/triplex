package com.triplex.dialer.model

/**
 * A "yank" request — the AI hit a roadblock during the call and needs
 * the user to provide information that the business is asking for.
 *
 * Example:
 * - "Please provide your date of birth for verification"
 * - "What is your order number?"
 * - "Enter the last 4 digits of your SSN"
 */
data class YankRequest(
    val companyName: String,
    val prompt: String,
    val inputFormat: YankInputFormat = YankInputFormat.TEXT,
)

/** Expected format for the user's response. */
enum class YankInputFormat(
    val label: String,
    val placeholder: String,
) {
    TEXT("Your answer", "Type here..."),
    PHONE_NUMBER("Phone number", "(555) 123-4567"),
    DATE("Date", "MM/DD/YYYY"),
    ORDER_NUMBER("Order number", "e.g. SAM-1234-5678"),
    EMAIL("Email address", "you@example.com"),
    ZIP_CODE("ZIP code", "12345"),
}
