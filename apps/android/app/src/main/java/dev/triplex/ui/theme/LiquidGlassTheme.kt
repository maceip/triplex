package dev.triplex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import zed.rainxch.rikkaui.foundation.RikkaAccentPreset
import zed.rainxch.rikkaui.foundation.RikkaPalette
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Triplex-specific colour roles that sit on top of the design system.
 *
 * Glass geometry (blur radius, refraction, tint and highlight strength) is not
 * defined here — it lives in `RikkaTheme.glass` and is consumed through the
 * glass components in `zed.rainxch.rikkaui.components.ui.glass`.
 */
object LiquidGlassDesign {
    val colors: LiquidGlassColors
        @Composable get() = LocalLiquidGlassColors.current
}

/**
 * Installs the RikkaUI design system plus the Triplex colour roles, and keeps
 * Material 3 available underneath for screens that have not been reskinned yet.
 *
 * The shell is dark by default rather than following the system. Glass reads as
 * glass only when there is contrast to refract: on a light shell the blurred
 * backdrop, the specular rim and the tint all collapse towards white and the
 * material disappears. Callers can still pass [darkTheme] explicitly.
 */
@Composable
fun LiquidGlassTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLiquidGlassColors provides if (darkTheme) LiquidGlassColorsDark else LiquidGlassColorsLight,
    ) {
        RikkaTheme(
            palette = RikkaPalette.Zinc,
            accent = RikkaAccentPreset.Violet,
            isDark = darkTheme,
        ) {
            TriplexTheme(
                darkTheme = darkTheme,
                dynamicColor = false,
                content = content,
            )
        }
    }
}
