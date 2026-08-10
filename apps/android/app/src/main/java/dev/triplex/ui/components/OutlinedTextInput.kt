package dev.triplex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaui.components.ui.input.Input
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The canonical Triplex text field.
 *
 * Wraps the design-system [Input], which is deliberately chrome-free: its
 * `label` and `errorMessage` are announced to assistive technology but never
 * drawn. Triplex forms need both on screen, so the caption above and the error
 * below are rendered here and the same strings are handed to [Input] for
 * semantics — one source of truth, two audiences.
 *
 * @param minLines reserves vertical room for multi-line fields. [Input] has no
 *   such parameter, so it becomes a minimum height: one 40dp row plus a line
 *   box for each additional line.
 */
@Composable
fun OutlinedTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    errorText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs)
    ) {
        Text(text = label, variant = TextVariant.Small)
        Input(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.defaultMinSize(
                minHeight = SingleRowHeight + ExtraRowHeight * (minLines - 1).coerceAtLeast(0)
            ),
            placeholder = placeholder.orEmpty(),
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            label = label,
            isError = errorText != null,
            errorMessage = errorText.orEmpty()
        )
        errorText?.let {
            Text(text = it, variant = TextVariant.Small, color = RikkaTheme.colors.destructive)
        }
    }
}

/** Matches the `defaultMinSize` the design-system [Input] applies to itself. */
private val SingleRowHeight = 40.dp

/** Line box of `RikkaTypography.p` — the style [Input] renders its text with. */
private val ExtraRowHeight = 22.dp
