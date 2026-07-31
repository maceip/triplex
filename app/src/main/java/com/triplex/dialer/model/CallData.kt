package com.triplex.dialer.model

import java.util.UUID

/** Represents a single call — mirrors AndroidX CallData with Triplex voice fields. */
data class CallData(
    val id: String = UUID.randomUUID().toString(),
    val dialedNumber: String = "",
    val callerName: String = "",
    val state: CallState = CallState.NEW,
    val startTimeMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val isIncoming: Boolean = false,
    val isTriplexAgent: Boolean = false,
    val triplexSessionId: String? = null,
    val errorMessage: String? = null,
) {
    fun displayLabel(): String =
        if (callerName.isNotBlank()) callerName else dialedNumber

    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
}
