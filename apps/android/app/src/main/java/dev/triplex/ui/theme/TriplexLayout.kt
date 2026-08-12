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

    /**
     * Widest a column of prose or form fields is allowed to get.
     *
     * An unfolded Fold is 840dp of window, and a line of body text that runs the
     * whole way across it is unreadable — the eye loses the return sweep. Main
     * columns are capped here and centred, which is what [triplexContentWidth]
     * does.
     */
    val contentMaxWidth: Dp = 600.dp

    /**
     * Widest the docked dialler is allowed to get.
     *
     * Narrower than [contentMaxWidth] because the dock is a *pad*, not a
     * column: full-bleed frost across an unfolded screen puts the keys a
     * thumb-stretch apart and turns the panel into a wall.
     */
    val dialDockMaxWidth: Dp = 360.dp

    /**
     * Ceiling on the travelling pill inside the bottom `GlassNavigationBar`.
     *
     * Two tabs across a 700dp bar give each a 350dp slot, and a pill that wide
     * behind the word "Agent" reads as a half-screen slab rather than a marker.
     */
    val navIndicatorMaxWidth: Dp = 180.dp

    /** Width reserved for the side rail that replaces the bottom bar when there is room. */
    val navigationRailWidth: Dp = 88.dp

    /** Width of the keypad's permanent directory pane on an expanded window. */
    val directoryPaneWidth: Dp = 360.dp

    /** Below this window width the shell is a phone: bottom bar, no width caps. */
    const val compactWidthDp: Int = 600

    /** Below this width the shell is a folded-open phone; at or above it, a tablet. */
    const val mediumWidthDp: Int = 840
}
