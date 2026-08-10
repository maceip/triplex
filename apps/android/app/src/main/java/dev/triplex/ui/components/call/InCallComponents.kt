package dev.triplex.ui.components.call

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaui.components.ui.glass.GlassDefaults
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassSurface
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme
import dev.triplex.ui.theme.TriplexDesign

/**
 * The in-call surface's visual primitives.
 *
 * They live here rather than in the screens for one reason: the screens under
 * `ui/call/` decide *what* a live call offers, and this layer decides what that
 * looks like. Anything the design system already ships — the sheet itself, the
 * dialpad, the orb — is used directly from `zed.rainxch.rikkaui` instead; what
 * is left here is the in-call chrome it has no equivalent for.
 */

/**
 * One control on the in-call action grid.
 *
 * Latching controls — mute, speaker, hold — carry their on/off condition in
 * [active] rather than in the label, so a screen reader announces the state
 * instead of the user having to infer it from a word change.
 *
 * @param icon the control's glyph, drawn above [label].
 * @param label the control's name, e.g. "Mute".
 * @param active whether the control is currently engaged.
 */
@Composable
fun TriplexCallActionTile(
    icon: IconToken,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) TriplexDesign.motion.pressedScale else 1f,
        animationSpec = spring(stiffness = 720f, dampingRatio = 0.78f),
        label = "Call action press",
    )
    val shape = GlassDefaults.shape()

    // Engaging a control lifts it from Subtle to Regular glass and washes the
    // accent over it, so "on" reads as the tile rising out of the surface
    // rather than as a colour swap a glance can miss.
    GlassSurface(
        modifier = modifier
            .width(96.dp)
            .defaultMinSize(minHeight = 68.dp)
            .scale(scale)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .semantics {
                this.role = Role.Button
                stateDescription = if (active) "On" else "Off"
            },
        level = if (active) GlassLevel.Regular else GlassLevel.Subtle,
        shape = shape,
        tint = if (active) RikkaTheme.colors.primary else RikkaTheme.glass.tint,
        contentPadding = PaddingValues(RikkaTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TriplexDesign.spacing.xSmall),
        ) {
            // No explicit tint: GlassSurface publishes the content colour the
            // label already reads, so the glyph follows the same accent swap
            // when the control latches on.
            Icon(imageVector = icon, contentDescription = null, size = IconSize.Lg)
            Text(text = label, variant = TextVariant.Small, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Who the call is with, and how long it has been running.
 *
 * The elapsed clock is passed in already formatted: the caller owns the tick, so
 * nothing here has to recompose on a timer it does not control.
 *
 * @param elapsed the call clock, or null when there is no connect time to count
 *   from — a screened SIP leg has none, and a fabricated one would be a lie.
 */
@Composable
fun TriplexCallHeader(
    callerName: String,
    callerNumber: String,
    statusLine: String,
    modifier: Modifier = Modifier,
    elapsed: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TriplexDesign.spacing.xSmall),
    ) {
        if (statusLine.isNotBlank()) {
            Text(text = statusLine.uppercase(), variant = TextVariant.Small)
        }
        Text(text = callerName, variant = TextVariant.H3, textAlign = TextAlign.Center)
        // The party already falls back to the number, so a second line that
        // repeats it is noise rather than detail.
        if (callerNumber.isNotBlank() && callerNumber != callerName) {
            Text(text = callerNumber, variant = TextVariant.Muted)
        }
        elapsed?.let { Text(text = it, variant = TextVariant.Large) }
    }
}
