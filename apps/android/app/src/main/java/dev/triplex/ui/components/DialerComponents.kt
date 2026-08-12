package dev.triplex.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaui.components.ui.button.Button
import zed.rainxch.rikkaui.components.ui.button.ButtonAnimation
import zed.rainxch.rikkaui.components.ui.button.ButtonSize
import zed.rainxch.rikkaui.components.ui.button.ButtonVariant
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.input.Input
import zed.rainxch.rikkaui.components.ui.input.InputDefaults
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The dialer's share of the component inventory (reskin.md §4).
 *
 * These are the pieces `DialerActivity` used to keep as private composables.
 * Lifting them here is what lets the keypad be assembled entirely from
 * wrappers: Phase 6 restyled the dialer by editing this file rather than by
 * touching a screen.
 */

/**
 * The number under construction, editable so a hardware keyboard still works.
 *
 * The design-system [Input] takes only an `IconToken` for its trailing slot,
 * so "Paste" is a sibling button in a Row rather than field chrome.
 */
@Composable
fun TriplexDialDisplay(
    value: String,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
    ) {
        val colors = RikkaTheme.colors
        // Translucent lozenge — opaque theme background punches a black hole in
        // the glass dock; sharp md corners fight the circular keys below.
        Input(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = "Enter a number",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            label = "Phone number",
            style = RikkaTheme.typography.h3.copy(textAlign = TextAlign.Center),
            shape = RoundedCornerShape(percent = 50),
            colors =
                InputDefaults.colors().copy(
                    background = Color.White.copy(alpha = 0.10f),
                    border = Color.White.copy(alpha = 0.18f),
                    focusedBorder = colors.ring.copy(alpha = 0.55f),
                    disabledBackground = Color.White.copy(alpha = 0.05f),
                ),
        )
        Button(
            text = "Paste",
            onClick = onPaste,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            animation = ButtonAnimation.Scale,
        )
    }
}

/** A directory or history row: identity on the left, one call action on the right. */
@Composable
fun TriplexListRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leadingIcon: IconToken? = null,
    actionIcon: IconToken? = null,
    actionContentDescription: String = "Call",
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * When true, draws no glass of its own — for rows that already sit inside a
     * [TriplexTray] or [zed.rainxch.rikkaui.components.ui.glass.GlassPanel].
     */
    embedded: Boolean = false,
) {
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            modifier = rowModifier.padding(
                horizontal = RikkaTheme.spacing.lg,
                vertical = RikkaTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Icon(imageVector = it, contentDescription = null, size = IconSize.Default)
                Spacer(Modifier.size(RikkaTheme.spacing.md))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    variant = TextVariant.Large,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        variant = TextVariant.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actionIcon != null && onAction != null) {
                TriplexIconButton(
                    imageVector = actionIcon,
                    contentDescription = actionContentDescription,
                    onClick = onAction,
                )
            }
        }
    }

    if (embedded) {
        val interaction = remember { MutableInteractionSource() }
        row(
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    } else {
        // Subtle, not the default Regular: these repeat down a list, and a column of
        // Regular slabs each casting its own shadow reads as a stack of floating
        // panels instead of rows on one surface.
        TriplexCard(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            level = GlassLevel.Subtle,
        ) {
            row(Modifier)
        }
    }
}

/** A day header in recents, or a section title in the directory. */
@Composable
fun TriplexSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
        variant = TextVariant.Small,
        // onMuted stays readable over dark scenery; primary washes out to a
        // faint purple that disappears on the glass tray.
        color = RikkaTheme.colors.onMuted,
    )
}

/** One outstanding consent, with the single button that resolves it. */
@Composable
fun TriplexSetupBanner(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TriplexTray(modifier = modifier, tone = TriplexCardTone.WARNING) {
        Column(
            modifier = Modifier.padding(RikkaTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
        ) {
            Text(text = title, variant = TextVariant.Large)
            Text(text = description, variant = TextVariant.P)
            TriplexButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                style = TriplexButtonStyle.OUTLINE,
            )
        }
    }
}

/** Shown when a list is legitimately empty, so blank space never reads as a bug. */
@Composable
fun TriplexEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = RikkaTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
    ) {
        Text(text = title, variant = TextVariant.Large)
        Text(
            text = message,
            variant = TextVariant.Muted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            TriplexButton(
                text = actionLabel,
                onClick = onAction,
                style = TriplexButtonStyle.OUTLINE,
            )
        }
    }
}
