package dev.triplex.ui.theme

import androidx.compose.ui.graphics.Color
import zed.rainxch.rikkaui.foundation.RikkaScenery
import zed.rainxch.rikkaui.foundation.RikkaSceneryLobe

/**
 * The brand pair, and the only literal colours left in the app.
 *
 * Every semantic role — primary, surface, destructive — now comes from
 * `RikkaTheme.colors` (Zinc + Violet, installed by [LiquidGlassTheme]). These
 * four survive because the scene behind the glass is not a semantic role: it is
 * Triplex's identity, and it has to stay recognisable across a palette swap.
 */
private val SignalViolet = Color(0xFF6558E8)
private val SignalVioletLight = Color(0xFFCAC3FF)
private val VoiceCyan = Color(0xFF006B75)
private val VoiceCyanLight = Color(0xFF62D9E6)

/**
 * Triplex's violet/cyan scene, tuned so glass refraction has something to act on.
 *
 * The previous [TriplexBackground] washed a near-flat gradient with two auras at
 * ~9–18% alpha. A 12–44dp lens over that lands on nearly the same colour it
 * started with, so liquid glass reads as a translucent panel with a rim.
 *
 * Mid-sized lobes at 0.32–0.58 alpha keep the Signal violet / Voice cyan identity
 * while giving the material real colour variance across a refraction step.
 */
fun triplexScenery(isDark: Boolean): RikkaScenery =
    if (isDark) {
        RikkaScenery(
            top = Color(0xFF151824),
            bottom = Color(0xFF090B12),
            lobes =
                listOf(
                    // Large washes — mood, not the optical work.
                    RikkaSceneryLobe(SignalVioletLight, centerX = 0.88f, centerY = 0.04f, radius = 0.70f, alpha = 0.58f),
                    RikkaSceneryLobe(VoiceCyanLight, centerX = 0.06f, centerY = 0.40f, radius = 0.62f, alpha = 0.46f),
                    // Mid lobes — steep enough that a 26dp displacement crosses a
                    // real colour change.
                    RikkaSceneryLobe(SignalViolet, centerX = 0.30f, centerY = 0.80f, radius = 0.34f, alpha = 0.50f),
                    RikkaSceneryLobe(Color(0xFF00A8B8), centerX = 0.78f, centerY = 0.66f, radius = 0.28f, alpha = 0.40f),
                    RikkaSceneryLobe(SignalVioletLight, centerX = 0.62f, centerY = 0.30f, radius = 0.22f, alpha = 0.36f),
                    RikkaSceneryLobe(VoiceCyanLight, centerX = 0.14f, centerY = 0.62f, radius = 0.20f, alpha = 0.32f),
                ),
            grainAlpha = 0.055f,
            vignetteAlpha = 0.32f,
        )
    } else {
        RikkaScenery(
            top = Color(0xFFF8F8FE),
            bottom = Color(0xFFF0F0F8),
            lobes =
                listOf(
                    RikkaSceneryLobe(SignalViolet, centerX = 0.88f, centerY = 0.04f, radius = 0.70f, alpha = 0.40f),
                    RikkaSceneryLobe(Color(0xFF00A8B8), centerX = 0.06f, centerY = 0.40f, radius = 0.62f, alpha = 0.34f),
                    RikkaSceneryLobe(SignalViolet, centerX = 0.30f, centerY = 0.80f, radius = 0.34f, alpha = 0.36f),
                    RikkaSceneryLobe(VoiceCyan, centerX = 0.78f, centerY = 0.66f, radius = 0.28f, alpha = 0.32f),
                    RikkaSceneryLobe(SignalVioletLight, centerX = 0.62f, centerY = 0.30f, radius = 0.22f, alpha = 0.28f),
                    RikkaSceneryLobe(VoiceCyanLight, centerX = 0.14f, centerY = 0.62f, radius = 0.20f, alpha = 0.30f),
                ),
            grainAlpha = 0.04f,
            vignetteAlpha = 0.16f,
        )
    }
