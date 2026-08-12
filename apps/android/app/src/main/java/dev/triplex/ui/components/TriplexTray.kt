package dev.triplex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassPanel
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.icon.RikkaIcons
import zed.rainxch.rikkaui.components.ui.separator.Separator
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * One glass slab that holds a whole section of furniture.
 *
 * The dialpad already collapses twelve lenses into a single tray below Full;
 * [TriplexTray] is that idea for lists and agent status. Rows, buttons, and
 * headers sit *on* the tray — they do not each sample the backdrop — so a
 * section costs one blur pass instead of one per card, and reads as a single
 * object rather than a pile of floating panels.
 *
 * [hostsGlass] stays off on purpose: anything that needs its own lens (a
 * VoiceOrb, a nested chip) should live outside the tray or in a panel that
 * explicitly opts into nesting.
 */
@Composable
fun TriplexTray(
    modifier: Modifier = Modifier,
    tone: TriplexCardTone = TriplexCardTone.DEFAULT,
    level: GlassLevel = GlassLevel.Regular,
    shapeRadius: Dp = TrayCornerRadius,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        level = level,
        shape = RoundedCornerShape(shapeRadius),
        tint = tone.glassTint(),
        contentColor = RikkaTheme.colors.onSurface,
        contentPadding = contentPadding,
        hostsGlass = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** Inset hairline between tray rows — inset so it never kisses the rounded rim. */
@Composable
fun TriplexTrayDivider(modifier: Modifier = Modifier) {
    Separator(
        modifier = modifier.padding(horizontal = RikkaTheme.spacing.lg),
        color = RikkaTheme.colors.border.copy(alpha = 0.45f),
    )
}

/**
 * A pressable row of content that lives *inside* a [TriplexTray].
 *
 * No glass of its own — the tray is the surface. A soft wash on press is the
 * only feedback, so a finger on a setup entry feels like glass without paying
 * for another sampler.
 */
@Composable
fun TriplexTrayRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = RikkaTheme.spacing.lg,
        vertical = RikkaTheme.spacing.md,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash = if (pressed && onClick != null && enabled) {
        RikkaTheme.colors.onSurface.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(wash)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.md),
        content = content,
    )
}

/**
 * Title block that opens a tray — eyebrow, title, optional muted description.
 *
 * Kept inside the tray so the section reads as one object from the first line
 * of type down through the rows beneath it.
 */
@Composable
fun TriplexTrayHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    description: String? = null,
) {
    val spacing = RikkaTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.lg,
                bottom = spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        eyebrow?.let {
            Text(
                text = it.uppercase(),
                variant = TextVariant.Small,
                color = RikkaTheme.colors.primary,
            )
        }
        Text(text = title, variant = TextVariant.H3)
        description?.let {
            Text(text = it, variant = TextVariant.Muted)
        }
    }
}

/**
 * Navigation-style row: title + muted detail + trailing chevron.
 *
 * Used for Agent setup entries and any tray row that pushes a nested screen.
 */
@Composable
fun TriplexTrayNavRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TriplexTrayRow(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs),
        ) {
            Text(text = title, variant = TextVariant.H4)
            Text(
                text = description,
                variant = TextVariant.Small,
                color = RikkaTheme.colors.onMuted,
            )
        }
        Icon(
            imageVector = RikkaIcons.ChevronRight,
            contentDescription = null,
            tint = RikkaTheme.colors.onMuted,
            size = IconSize.Sm,
        )
    }
}

/** Matches the dial dock / directory sheet corner so trays feel like one family. */
private val TrayCornerRadius = 24.dp
