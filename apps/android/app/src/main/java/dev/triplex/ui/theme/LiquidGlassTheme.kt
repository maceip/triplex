package dev.triplex.ui.theme

import androidx.compose.runtime.Composable
import zed.rainxch.rikkaui.foundation.RikkaAccentPreset
import zed.rainxch.rikkaui.foundation.RikkaPalette
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Installs Triplex's RikkaUI theme: Zinc + Violet, dark by default, with the
 * brand violet/cyan [triplexScenery] as [RikkaTheme.scenery].
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
    RikkaTheme(
        palette = RikkaPalette.Zinc,
        accent = RikkaAccentPreset.Violet,
        isDark = darkTheme,
        scenery = triplexScenery(darkTheme),
        content = content,
    )
}
