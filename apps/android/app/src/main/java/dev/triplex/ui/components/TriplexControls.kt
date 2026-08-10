package dev.triplex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import zed.rainxch.rikkaui.components.ui.glass.GlassChip
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.components.ui.toggle.Toggle
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * A labelled setting with a switch.
 *
 * Lives here rather than in a screen so the pairing of title, consequence and
 * control is defined once; screens consume this wrapper and never reach for the
 * design-system [Toggle] directly.
 *
 * @param supportingText the consequence of the setting, not a restatement of
 *   its title. Settings that change what happens on a live call say so.
 */
@Composable
fun TriplexToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs),
        ) {
            Text(text = title, variant = TextVariant.Large)
            supportingText?.let {
                Text(text = it, variant = TextVariant.Muted)
            }
        }
        Spacer(Modifier.width(RikkaTheme.spacing.md))
        Toggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            label = title,
        )
    }
}

/**
 * A selectable chip.
 *
 * Same rationale as [TriplexToggleRow]. [GlassChip] carries the selected state
 * itself — subtle glass when idle, accent-tinted and lifted when selected — so
 * this wrapper only supplies the label.
 */
@Composable
fun TriplexChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GlassChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(text = label, variant = TextVariant.Small)
    }
}
