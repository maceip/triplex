package dev.triplex.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.triplex.ui.theme.TriplexDesign
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

enum class VoiceSpherePhase {
    RESTING,
    LISTENING,
    PROCESSING,
    READY,
    PLAYING,
    ERROR
}

/**
 * Triplex's voice signature: a lightweight, canvas-rendered sphere whose
 * internal ribbons react to microphone energy. It deliberately uses no
 * bitmap, shader runtime, or model work, so the same component remains smooth
 * across the supported Android tier.
 */
@Composable
fun TriplexVoiceSphere(
    phase: VoiceSpherePhase,
    amplitude: Float,
    modifier: Modifier = Modifier,
    contentDescription: String = "Voice capture visualization"
) {
    val palette = TriplexDesign.colors
    val targetColor = when (phase) {
        VoiceSpherePhase.RESTING -> MaterialTheme.colorScheme.primary
        VoiceSpherePhase.LISTENING -> MaterialTheme.colorScheme.secondary
        VoiceSpherePhase.PROCESSING -> MaterialTheme.colorScheme.primary
        VoiceSpherePhase.READY -> palette.success
        VoiceSpherePhase.PLAYING -> MaterialTheme.colorScheme.secondary
        VoiceSpherePhase.ERROR -> MaterialTheme.colorScheme.error
    }
    val sphereColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(TriplexDesign.motion.expressiveMillis),
        label = "Voice sphere color"
    )
    val inputEnergy by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(90),
        label = "Voice sphere input energy"
    )

    val motion = rememberInfiniteTransition(label = "Voice sphere motion")
    val orbitDegrees by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Voice sphere orbit"
    )
    val breathing by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Voice sphere breath"
    )

    val effectiveEnergy = when (phase) {
        VoiceSpherePhase.LISTENING -> inputEnergy
        VoiceSpherePhase.PLAYING -> max(inputEnergy, 0.38f + breathing * 0.25f)
        VoiceSpherePhase.PROCESSING -> 0.18f + breathing * 0.18f
        VoiceSpherePhase.READY -> 0.10f + breathing * 0.08f
        VoiceSpherePhase.ERROR -> 0.08f
        VoiceSpherePhase.RESTING -> 0.05f + breathing * 0.04f
    }.coerceIn(0f, 1f)

    val highlightColor = lerp(
        sphereColor,
        MaterialTheme.colorScheme.onBackground,
        0.48f
    )
    val deepColor = lerp(palette.surfaceSunken, sphereColor, 0.58f)
    val ribbonColor = MaterialTheme.colorScheme.onBackground
    val secondaryRibbon = MaterialTheme.colorScheme.secondary
    val scrim = MaterialTheme.colorScheme.scrim

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val diameter = size.minDimension
        val center = this.center
        val coreRadius = diameter * (0.335f + effectiveEnergy * 0.012f)
        val haloRadius = coreRadius * (1.34f + effectiveEnergy * 0.13f)
        val phaseRadians = orbitDegrees / 180f * PI.toFloat()

        drawOval(
            color = scrim.copy(alpha = 0.18f),
            topLeft = Offset(
                center.x - coreRadius * 0.72f,
                center.y + coreRadius * 0.82f
            ),
            size = Size(coreRadius * 1.44f, coreRadius * 0.34f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    sphereColor.copy(alpha = 0.30f + effectiveEnergy * 0.16f),
                    sphereColor.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = haloRadius
            ),
            radius = haloRadius,
            center = center
        )

        repeat(2) { ring ->
            val ringRadius = coreRadius * (1.12f + ring * 0.12f + effectiveEnergy * 0.05f)
            drawCircle(
                color = sphereColor.copy(alpha = 0.16f - ring * 0.045f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = diameter * 0.0045f)
            )
        }

        val sphereBounds = Rect(
            left = center.x - coreRadius,
            top = center.y - coreRadius,
            right = center.x + coreRadius,
            bottom = center.y + coreRadius
        )
        val sphereClip = Path().apply { addOval(sphereBounds) }

        clipPath(sphereClip) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(highlightColor, sphereColor, deepColor),
                    center = Offset(
                        center.x - coreRadius * 0.28f + sin(phaseRadians) * coreRadius * 0.04f,
                        center.y - coreRadius * 0.32f
                    ),
                    radius = coreRadius * 1.34f
                ),
                radius = coreRadius,
                center = center
            )

            rotate(degrees = -13f + orbitDegrees * 0.055f, pivot = center) {
                repeat(3) { index ->
                    val compression = 0.32f + index * 0.18f
                    drawOval(
                        color = ribbonColor.copy(alpha = 0.055f + index * 0.018f),
                        topLeft = Offset(
                            center.x - coreRadius * compression,
                            center.y - coreRadius * 1.04f
                        ),
                        size = Size(
                            coreRadius * compression * 2f,
                            coreRadius * 2.08f
                        ),
                        style = Stroke(width = diameter * 0.0035f)
                    )
                }
            }

            repeat(5) { band ->
                val verticalOffset = (band - 2) * coreRadius * 0.17f
                val bandStrength = 1f - abs(band - 2) * 0.12f
                val waveHeight = coreRadius *
                    (0.025f + effectiveEnergy * 0.17f) * bandStrength
                val direction = if (band % 2 == 0) 1f else -1f
                val samples = 44
                val waveBrush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        secondaryRibbon.copy(alpha = 0.16f + effectiveEnergy * 0.20f),
                        ribbonColor.copy(alpha = 0.52f + effectiveEnergy * 0.30f),
                        secondaryRibbon.copy(alpha = 0.16f + effectiveEnergy * 0.20f),
                        Color.Transparent
                    ),
                    startX = sphereBounds.left,
                    endX = sphereBounds.right
                )
                var previousPoint: Offset? = null
                repeat(samples + 1) { sample ->
                    val normalized = sample.toFloat() / samples
                    val x = sphereBounds.left + normalized * sphereBounds.width
                    val envelope = sin(normalized * PI.toFloat()).coerceAtLeast(0f)
                    val wave = sin(
                        normalized * PI.toFloat() * (2.25f + band * 0.16f) +
                            phaseRadians * direction + band * 0.68f
                    )
                    val y = center.y + verticalOffset + wave * waveHeight * envelope
                    val point = Offset(x, y)
                    previousPoint?.let { previous ->
                        drawLine(
                            brush = waveBrush,
                            start = previous,
                            end = point,
                            strokeWidth = diameter * (0.006f + effectiveEnergy * 0.003f),
                            cap = StrokeCap.Butt
                        )
                    }
                    previousPoint = point
                }
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ribbonColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(
                        center.x - coreRadius * 0.38f,
                        center.y - coreRadius * 0.42f
                    ),
                    radius = coreRadius * 0.62f
                ),
                radius = coreRadius * 0.62f,
                center = Offset(
                    center.x - coreRadius * 0.38f,
                    center.y - coreRadius * 0.42f
                )
            )
        }

        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    highlightColor.copy(alpha = 0.72f),
                    sphereColor.copy(alpha = 0.18f),
                    deepColor.copy(alpha = 0.54f),
                    highlightColor.copy(alpha = 0.72f)
                ),
                center = center
            ),
            radius = coreRadius,
            center = center,
            style = Stroke(width = diameter * 0.008f)
        )
    }
}
