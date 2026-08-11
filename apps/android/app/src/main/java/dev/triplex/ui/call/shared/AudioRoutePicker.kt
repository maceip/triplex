package dev.triplex.ui.call.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.triplex.domain.call.AudioEndpointUi
import dev.triplex.ui.components.TriplexChoiceChip
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * Where the call's audio comes out.
 *
 * Chips rather than a menu because the set is short, the current choice has to
 * be visible without opening anything, and switching output mid-call is
 * something people do while holding the phone to their ear.
 *
 * Draws nothing when there is nothing to choose between — a single endpoint is
 * not a choice, and a picker with one option implies the user did something
 * wrong when the second one does not appear.
 */
@Composable
fun AudioRoutePicker(
    endpoints: List<AudioEndpointUi>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (endpoints.size < 2) return
    val spacing = RikkaTheme.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(text = "Audio", variant = TextVariant.Small)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            endpoints.forEach { endpoint ->
                TriplexChoiceChip(
                    label = endpoint.name,
                    selected = endpoint.selected,
                    onClick = { onSelect(endpoint.id) },
                )
            }
        }
    }
}
