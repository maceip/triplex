package dev.triplex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Role colours for call and agent UI.
 *
 * This is deliberately *not* a glass palette. The glass material itself — blur,
 * refraction, tint, rim, shadow — is owned by
 * [zed.rainxch.rikkaui.foundation.RikkaGlass] and read through
 * `RikkaTheme.glass`. What lives here is the app-specific meaning that glass has
 * no opinion about: who is speaking, and whether an action answers or ends a
 * call.
 *
 * Keeping the two apart matters in practice: a glass surface derives its
 * appearance from whatever is behind it, so baking fixed surface colours into a
 * palette is how you end up with panels that no longer refract anything.
 */
@Immutable
data class LiquidGlassColors(
    /** Tint for surfaces that represent the agent itself. */
    val agentAccent: Color,
    /** Chat bubble tint for the remote party. */
    val callerBubble: Color,
    /** Chat bubble tint for the device owner. */
    val userBubble: Color,
    /** Accept-call affordance. */
    val answerGreen: Color,
    /** Decline / hang-up affordance. */
    val declineRed: Color,
    /** Transcript timeline marker for agent turns. */
    val timelineAgent: Color,
    /** Transcript timeline marker for caller turns. */
    val timelineCaller: Color,
    /** Transcript timeline marker for owner turns. */
    val timelineUser: Color,
)

/**
 * Roles read against a dark, blurred backdrop: saturated enough to survive the
 * tint wash that sits over them.
 */
val LiquidGlassColorsDark = LiquidGlassColors(
    agentAccent = Color(0xFF66BB6A),
    callerBubble = Color(0xFF42A5F5),
    userBubble = Color(0xFFAB47BC),
    answerGreen = Color(0xFF4CAF50),
    declineRed = Color(0xFFF44336),
    timelineAgent = Color(0xFF81C784),
    timelineCaller = Color(0xFF64B5F6),
    timelineUser = Color(0xFFBA68C8),
)

/** The same roles pitched darker, so they hold up over a light backdrop. */
val LiquidGlassColorsLight = LiquidGlassColors(
    agentAccent = Color(0xFF2E7D32),
    callerBubble = Color(0xFF1565C0),
    userBubble = Color(0xFF6A1B9A),
    answerGreen = Color(0xFF2E7D32),
    declineRed = Color(0xFFC62828),
    timelineAgent = Color(0xFF388E3C),
    timelineCaller = Color(0xFF1976D2),
    timelineUser = Color(0xFF7B1FA2),
)

internal val LocalLiquidGlassColors = staticCompositionLocalOf { LiquidGlassColorsDark }
