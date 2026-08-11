package dev.triplex.ui.call.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaicons.tokens.GitMerge
import zed.rainxch.rikkaicons.tokens.Grid
import zed.rainxch.rikkaicons.tokens.MicOff
import zed.rainxch.rikkaicons.tokens.Pause
import zed.rainxch.rikkaicons.tokens.Repeat
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaicons.tokens.Shuffle
import zed.rainxch.rikkaicons.tokens.UserPlus
import zed.rainxch.rikkaicons.tokens.VolumeHigh
import zed.rainxch.rikkaui.components.ui.call.CallActionTile
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * A control on a live call's action grid.
 *
 * A control the platform does not offer is **absent**, not disabled. These are
 * direct manipulations of a running call, and a greyed "Merge" on a call with
 * nothing to merge into explains nothing; the reasons a user actually needs
 * belong to the agent panel, which is where they are told what Triplex can and
 * cannot do for them.
 *
 * This is the description of a control, not its appearance — the design system's
 * [CallActionTile] draws it.
 */
data class CallAction(
    val id: CallActionId,
    val label: String,
    /** True when the control is currently engaged — muted, on speaker, on hold. */
    val active: Boolean = false,
)

enum class CallActionId {
    MUTE,
    SPEAKER,
    HOLD,
    ADD_CALL,
    SWITCH,
    MERGE,
    SWAP,
    KEYPAD,
}

/**
 * Renders whatever tiles a call surface derived, in the order it derived them.
 *
 * Deliberately dumb: it does no capability checks of its own, because the state
 * that produced [tiles] already did them once, in a pure function a JVM test can
 * assert. Two sets of gating rules would eventually disagree.
 *
 * Rows are fixed-width rather than flowed so tiles line up in a column across
 * rows; the trailing gap in a short last row is filled with weighted spacers.
 */
@Composable
fun CallActionGrid(
    tiles: List<CallAction>,
    onAction: (CallActionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) return
    val spacing = RikkaTheme.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        tiles.chunked(TILES_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { tile ->
                    CallActionTile(
                        icon = tile.id.icon(),
                        label = tile.label,
                        active = tile.active,
                        onClick = { onAction(tile.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(TILES_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The glyph for each control.
 *
 * Latching controls are drawn in their *off-state* verb — MUTE is a struck-out
 * mic, not a mic — because the tile already announces on/off through its glass
 * level and `stateDescription`, and a second, contradictory signal in the glyph
 * is what makes mute buttons ambiguous.
 *
 * Three of these are stand-ins, since the token vocabulary is semantic rather
 * than telephony-specific: SWITCH borrows `Repeat` (swap between lines), MERGE
 * borrows `GitMerge` (two strands becoming one) and SWAP borrows `Shuffle`.
 */
private fun CallActionId.icon(): IconToken =
    when (this) {
        CallActionId.MUTE -> RikkaIcons.MicOff
        CallActionId.SPEAKER -> RikkaIcons.VolumeHigh
        CallActionId.HOLD -> RikkaIcons.Pause
        CallActionId.ADD_CALL -> RikkaIcons.UserPlus
        CallActionId.SWITCH -> RikkaIcons.Repeat
        CallActionId.MERGE -> RikkaIcons.GitMerge
        CallActionId.SWAP -> RikkaIcons.Shuffle
        CallActionId.KEYPAD -> RikkaIcons.Grid
    }

private const val TILES_PER_ROW = 3
