package dev.triplex.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp

/**
 * How much horizontal room the window has, in the three sizes the shell reacts to.
 *
 * A class rather than a raw width because every consumer wants the same three
 * answers — bar or rail, capped column or full bleed, one pane or two — and
 * scattering `if (widthDp > 600)` across the screens is how those three drift
 * apart.
 */
enum class TriplexWidthClass {
    /** A phone, or a folded Fold. */
    Compact,

    /** An unfolded Fold, a small tablet, or a large phone in landscape. */
    Medium,

    /** A tablet, or a desktop-sized window. */
    Expanded,
}

/** The bottom bar becomes a side rail as soon as there is width to spare for it. */
val TriplexWidthClass.useNavigationRail: Boolean
    get() = this != TriplexWidthClass.Compact

/** Main columns stop growing once the window is wider than a readable measure. */
val TriplexWidthClass.capContentWidth: Boolean
    get() = this != TriplexWidthClass.Compact

/**
 * The window's width class, for anything below the shell.
 *
 * Provided once by the shell so a screen does not have to take a parameter it
 * only forwards. Defaults to [TriplexWidthClass.Compact], which is both the
 * common case and the layout that survives being wrong.
 */
val LocalTriplexWidthClass = staticCompositionLocalOf { TriplexWidthClass.Compact }

/**
 * Classifies the current window width.
 *
 * Reads the *window*, not the display: in split screen and in a Fold's
 * flex-mode app pair the app gets a fraction of the panel, and laying out a
 * rail for a screen the app does not own is worse than not having one.
 */
@Composable
fun rememberTriplexWidthClass(): TriplexWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        when {
            widthDp < TriplexLayout.compactWidthDp -> TriplexWidthClass.Compact
            widthDp < TriplexLayout.mediumWidthDp -> TriplexWidthClass.Medium
            else -> TriplexWidthClass.Expanded
        }
    }
}

/**
 * Caps this content at [maxWidth] and centres it, without changing the space it
 * occupies in its parent.
 *
 * A layout modifier rather than a `Box(contentAlignment = Center)` wrapper
 * because the caller is usually a scrolling container: the scrollbar, the
 * touch slop and the recorded glass backdrop should all still span the whole
 * window, and only the *content* should be narrow. Reporting the parent's width
 * and placing a narrower child inside it is exactly that split.
 *
 * On [TriplexWidthClass.Compact] it is the identity modifier — a phone has no
 * width to give away.
 */
fun Modifier.triplexContentWidth(
    widthClass: TriplexWidthClass,
    maxWidth: Dp = TriplexLayout.contentMaxWidth,
): Modifier =
    if (!widthClass.capContentWidth) {
        this
    } else {
        layout { measurable, constraints ->
            val cap = maxWidth.roundToPx()
            val target =
                if (constraints.hasBoundedWidth) minOf(constraints.maxWidth, cap) else cap
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = minOf(constraints.minWidth, target),
                    maxWidth = target,
                ),
            )
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
            layout(width, placeable.height) {
                placeable.place((width - placeable.width) / 2, 0)
            }
        }
    }

/** [triplexContentWidth] against the width class the shell provided. */
@Composable
fun Modifier.triplexContentWidth(): Modifier =
    triplexContentWidth(LocalTriplexWidthClass.current)
