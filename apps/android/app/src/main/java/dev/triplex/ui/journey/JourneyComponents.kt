package dev.triplex.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.theme.TriplexLayout
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Marketing-led journey chrome: brand-first hero, sparse stage rail, soft
 * floating shapes. Kept static by default so these screens do not reintroduce
 * the idle RenderThread heat Workstream 0 fixed.
 */
@Composable
fun JourneyHero(
    brand: String,
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    val spacing = RikkaTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        eyebrow?.let {
            TriplexStatusPill(text = it, tone = TriplexCardTone.ACCENT)
        }
        Text(
            text = brand,
            variant = TextVariant.Large,
            color = RikkaTheme.colors.primary,
        )
        Text(text = title, variant = TextVariant.H1)
        Text(
            text = supporting,
            variant = TextVariant.P,
            color = RikkaTheme.colors.onMuted,
        )
    }
}

@Composable
fun JourneyStageRail(
    stages: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = RikkaTheme.spacing
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, label ->
            val active = index == currentIndex
            val done = index < currentIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Canvas(modifier = Modifier.size(if (active) 12.dp else 8.dp)) {
                    drawCircle(
                        color = when {
                            active -> Color.White.copy(alpha = 0.95f)
                            done -> Color.White.copy(alpha = 0.55f)
                            else -> Color.White.copy(alpha = 0.22f)
                        },
                    )
                }
                Text(
                    text = label,
                    variant = TextVariant.Small,
                    color = if (active) {
                        RikkaTheme.colors.onBackground
                    } else {
                        RikkaTheme.colors.onMuted
                    },
                )
            }
        }
    }
}

/**
 * Soft static lobes behind journey content. Not wired to scenery drift —
 * intentional shapes for marketing rhythm without continuous invalidation.
 */
@Composable
fun FloatingShapeBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val primary = RikkaTheme.colors.primary
    val secondary = RikkaTheme.colors.secondary
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(w * 0.86f, h * 0.08f),
                    radius = w * 0.55f,
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(w * 0.08f, h * 0.42f),
                    radius = w * 0.48f,
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * 0.72f, h * 0.78f),
                    radius = w * 0.36f,
                ),
            )
        }
        content()
    }
}

@Composable
fun JourneyScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = RikkaTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TriplexLayout.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
        content = content,
    )
}

@Composable
fun JourneyStagger(
    index: Int,
    content: @Composable () -> Unit,
) {
    TriplexReveal(delayMillis = TriplexLayout.staggerMillis * index) {
        content()
    }
}

@Composable
fun JourneySpacer(height: Dp = 24.dp) {
    Spacer(modifier = Modifier.height(height))
}
