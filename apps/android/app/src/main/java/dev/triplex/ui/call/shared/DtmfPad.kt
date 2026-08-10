package dev.triplex.ui.call.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import zed.rainxch.rikkaui.components.ui.call.DialpadKey
import zed.rainxch.rikkaui.components.ui.call.GlassDialpad

/**
 * The ITU-T keys, minus everything a tone pad has no use for.
 *
 * No letters — nobody spells a name into a live line — and no `longPressDigit`,
 * because `+` is not a tone.
 */
private val DTMF_KEYS: List<DialpadKey> =
    "123456789*0#".map { DialpadKey(it) }

/**
 * The tone pad shown on a connected call.
 *
 * Separate from the dialler's keypad on purpose: this one sends a tone down a
 * live line on every press and has no number under construction, no backspace
 * and no long-press `+`. It is the design system's [GlassDialpad] with a
 * stripped key list, so the keys are identical to the dialler's without
 * pretending the two surfaces do the same job.
 */
@Composable
fun DtmfPad(
    onDigit: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassDialpad(
        onKeyPress = onDigit,
        modifier = modifier,
        keys = DTMF_KEYS,
    )
}
