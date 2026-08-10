package dev.triplex.ui.shell

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation graph for the single application shell.
 *
 * Routes are declared as `@Serializable` objects and consumed through the
 * Navigation Compose type-safe API (`composable<T>` / `navigation<T>`), so a
 * destination cannot be reached by a mistyped string.
 *
 * The map mirrors the approved plan (reskin.md §2.2):
 *
 * ```
 * DialerActivity (shell)
 * ├─ [gate] enrollment          — gates the agent tab until a device token exists
 * ├─ Tab: keypad (start)        — the dialer surface
 * └─ Tab: agent                 — agent home
 *     ├─ agent/inbound          — inbound screening setup
 *     ├─ agent/outbound         — outbound task setup
 *     ├─ agent/voice            — voice clone enrollment
 *     └─ agent/run/{runId}      — transcript of one recorded run
 * ```
 */
sealed interface ShellRoute {

    /** Dialer home: keypad, recents, contacts, favorites. Start destination. */
    @Serializable
    data object Keypad : ShellRoute

    /** Nested graph backing the agent tab; owns the tab's own back stack. */
    @Serializable
    data object AgentGraph : ShellRoute

    /** Agent tab root. */
    @Serializable
    data object AgentHome : ShellRoute

    /** `agent/inbound` — what the agent does when a call comes in. */
    @Serializable
    data object AgentInbound : ShellRoute

    /** `agent/outbound` — describe a call the agent should place. */
    @Serializable
    data object AgentOutbound : ShellRoute

    /** `agent/voice` — voice clone enrollment. */
    @Serializable
    data object AgentVoice : ShellRoute

    /**
     * `agent/run/{runId}` — the recorded transcript of a single agent call.
     *
     * @param runId the [dev.triplex.data.local.db.AgentCallRunEntity] id.
     */
    @Serializable
    data class AgentRunDetail(val runId: String) : ShellRoute
}
