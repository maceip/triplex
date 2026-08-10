package dev.triplex.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four window insets the shell has to place content inside of.
 *
 * A plain value type rather than `WindowInsets` so the policy that consumes it
 * is decidable without a composition — see [resolveShellContentInsets].
 */
data class ShellInsets(
    val left: Dp = 0.dp,
    val top: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp,
)

/**
 * Splits the system insets between the shell's scaffold content and its bottom
 * bar.
 *
 * **Insets belong to the shell, not to the screens.** `DialerActivity` goes edge
 * to edge and every destination is laid out inside whatever this returns, so no
 * screen calls `statusBarsPadding()` or `navigationBarsPadding()` for itself —
 * doing so on top of this would pad twice.
 *
 * The bottom is the only interesting case. The floating glass nav bar is drawn
 * *over* the gesture area and pads itself by [ShellInsets.bottom], and the
 * scaffold already reserves the bar's full measured height for content. Handing
 * the same inset to the content as well would reserve the gesture strip twice,
 * so it is dropped whenever the bar is present. With the bar gone — a call owns
 * the screen — nothing else is reserving that strip and the content takes it.
 *
 * @param system the raw system-bar (plus display-cutout) insets.
 * @param hasBottomBar whether the shell is currently rendering its nav bar.
 */
fun resolveShellContentInsets(system: ShellInsets, hasBottomBar: Boolean): ShellInsets =
    if (hasBottomBar) system.copy(bottom = 0.dp) else system
