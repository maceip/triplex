package dev.triplex.ui.call.incoming

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassSurface
import zed.rainxch.rikkaui.components.ui.glass.GlassTreatment

/**
 * The three-way slide control for a call that is still being decided.
 *
 * Moved here from `DialerActivity` unchanged, gesture and semantics included:
 * a drag is unusable one-handed on a lock screen for anyone who cannot make it,
 * so the three custom accessibility actions are the control, not a courtesy.
 * The incoming sheet uses it as its lock-screen presentation.
 */
@Composable
internal fun IncomingCallDecisionSlider(
    onDecline: () -> Unit,
    onAnswer: () -> Unit,
    onTransfer: () -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val horizontalLimit = with(density) { 118.dp.toPx() }
    val downwardLimit = with(density) { 92.dp.toPx() }
    val commitDistance = with(density) { 76.dp.toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun commit(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
        dragOffset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .semantics {
                contentDescription = "Incoming call control. Slide left to decline, right to answer, or down for the Triplex agent."
                customActions = listOf(
                    CustomAccessibilityAction("Decline call") { onDecline(); true },
                    CustomAccessibilityAction("Answer call") { onAnswer(); true },
                    CustomAccessibilityAction("Transfer to Triplex agent") { onTransfer(); true }
                )
            }
            .pointerInput(onDecline, onAnswer, onTransfer) {
                detectDragGestures(
                    onDragCancel = { dragOffset = Offset.Zero },
                    onDragEnd = {
                        when {
                            dragOffset.x <= -commitDistance -> commit(onDecline)
                            dragOffset.x >= commitDistance -> commit(onAnswer)
                            dragOffset.y >= commitDistance -> commit(onTransfer)
                            else -> dragOffset = Offset.Zero
                        }
                    }
                ) { change, amount ->
                    change.consume()
                    val candidate = dragOffset + amount
                    dragOffset = if (abs(candidate.x) > abs(candidate.y)) {
                        Offset(candidate.x.coerceIn(-horizontalLimit, horizontalLimit), 0f)
                    } else {
                        Offset(0f, candidate.y.coerceIn(0f, downwardLimit))
                    }
                }
            }
    ) {
        // The two tracks are glass, not fills: they sit over whatever the call
        // screen is showing, and the translucency they already asked for with
        // alpha 0.78 is the material's job rather than a colour trick.
        GlassSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 38.dp)
                .fillMaxWidth(0.78f)
                .height(68.dp),
            level = GlassLevel.Subtle,
            shape = CircleShape,
            tint = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentPadding = PaddingValues(0.dp)
        ) {}
        GlassSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
                .width(68.dp)
                .height(122.dp),
            level = GlassLevel.Subtle,
            shape = CircleShape,
            tint = MaterialTheme.colorScheme.tertiaryContainer,
            contentPadding = PaddingValues(0.dp)
        ) {}

        Text(
            "Decline",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 62.dp),
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Accept",
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 62.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Agent",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold
        )

        // The thumb is the one piece that must stay a solid object under the
        // finger, so it takes the smoked treatment: still glass, but colour-dense
        // enough to read as primary against whatever is behind the call screen.
        // Its drop shadow now comes from the prominent glass level rather than a
        // hand-set elevation.
        GlassSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 42.dp)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .size(60.dp),
            level = GlassLevel.Prominent,
            shape = CircleShape,
            tint = MaterialTheme.colorScheme.primary,
            treatment = GlassTreatment.Smoked,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            contentPadding = PaddingValues(0.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Slide", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}
