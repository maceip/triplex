package dev.triplex.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout constants that are Triplex-specific and not covered by [zed.rainxch.rikkaui.foundation.RikkaTheme].
 *
 * Spacing, motion durations, elevation, and semantic colours come from
 * `RikkaTheme` directly. Keep this object small — if a value maps onto a Rikka
 * token, use the token.
 */
object TriplexLayout {
    val screenHorizontal: Dp = 20.dp
    val screenTop: Dp = 12.dp

    /** Nested Agent push scale (NavHost pop transitions). */
    const val navigationScale: Float = 0.94f

    /** Stagger between sequential [dev.triplex.ui.components.TriplexReveal]s. */
    const val staggerMillis: Int = 42
}
