package dev.triplex.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Public Triplex design-language API.
 *
 * Screens use MaterialTheme for standard Material roles and this object for
 * Triplex-specific spacing, elevation, motion, and semantic colors. Keeping
 * these values here prevents one-off visual decisions from leaking into UI.
 */
object TriplexDesign {
    val colors: TriplexExtendedColors
        @Composable get() = LocalTriplexExtendedColors.current

    val spacing: TriplexSpacing
        @Composable get() = LocalTriplexSpacing.current

    val elevation: TriplexElevation
        @Composable get() = LocalTriplexElevation.current

    val motion: TriplexMotion
        @Composable get() = LocalTriplexMotion.current
}

@Immutable
data class TriplexSpacing(
    val hairline: Dp = 2.dp,
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val xLarge: Dp = 24.dp,
    val xxLarge: Dp = 32.dp,
    val huge: Dp = 48.dp,
    val screenHorizontal: Dp = 20.dp,
    val screenTop: Dp = 12.dp
)

@Immutable
data class TriplexElevation(
    val flat: Dp = 0.dp,
    val resting: Dp = 1.dp,
    val raised: Dp = 4.dp,
    val floating: Dp = 10.dp
)

@Immutable
data class TriplexMotion(
    val instantMillis: Int = 90,
    val quickMillis: Int = 160,
    val standardMillis: Int = 240,
    val expressiveMillis: Int = 420,
    val staggerMillis: Int = 42,
    val pressedScale: Float = 0.975f,
    val navigationScale: Float = 0.94f
)

internal val LocalTriplexExtendedColors = staticCompositionLocalOf {
    TriplexLightExtendedColors
}

internal val LocalTriplexSpacing = staticCompositionLocalOf { TriplexSpacing() }
internal val LocalTriplexElevation = staticCompositionLocalOf { TriplexElevation() }
internal val LocalTriplexMotion = staticCompositionLocalOf { TriplexMotion() }
