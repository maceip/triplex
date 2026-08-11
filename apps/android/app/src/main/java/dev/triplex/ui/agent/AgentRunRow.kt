package dev.triplex.ui.agent

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.triplex.data.local.db.AgentRunEndReason
import dev.triplex.ui.components.TriplexCardTone
import zed.rainxch.rikkaui.components.ui.call.CallHistoryItem
import dev.triplex.domain.call.CallDirection as DomainCallDirection
import zed.rainxch.rikkaui.components.ui.call.CallDirection as GlassCallDirection

/**
 * One recorded call in the agent-home list.
 *
 * The same [CallHistoryItem] the dialer's recents use, so a call the agent
 * handled and a call you took yourself read as the same kind of thing. The
 * outcome rides the badge slot; there is no call-back or delete intent behind
 * this list, so both swipe actions stay hidden.
 */
@Composable
fun AgentRunRow(
    run: AgentRun,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CallHistoryItem(
        name = run.counterpartName,
        modifier = modifier,
        detail = run.outcomeSummary?.takeIf(String::isNotBlank).orEmpty(),
        timestamp = relativeTime(run.startedAtMs),
        direction = run.toCallDirection(),
        badge = run.outcomeLabel(),
        onClick = onClick,
    )
}

/**
 * A run the agent never got to answer is a missed call, whichever way it was
 * pointing; everything else keeps the direction it was placed in.
 */
private fun AgentRun.toCallDirection(): GlassCallDirection = when {
    endReason == AgentRunEndReason.MISSED -> GlassCallDirection.Missed
    direction == DomainCallDirection.OUTGOING -> GlassCallDirection.Outgoing
    else -> GlassCallDirection.Incoming
}

/**
 * An unfinished run says so. It is either live or the process died mid-call, and
 * both are more useful to the user than a fabricated outcome.
 */
internal fun AgentRun.outcomeLabel(): String = when {
    unfinished -> "UNFINISHED"
    endReason == AgentRunEndReason.MISSED -> "MISSED"
    endReason == AgentRunEndReason.COMPLETED -> "HANDLED"
    else -> "ENDED"
}

internal fun AgentRun.outcomeTone(): TriplexCardTone = when {
    unfinished -> TriplexCardTone.WARNING
    endReason == AgentRunEndReason.MISSED -> TriplexCardTone.DANGER
    endReason == AgentRunEndReason.COMPLETED -> TriplexCardTone.SUCCESS
    else -> TriplexCardTone.DEFAULT
}

internal fun relativeTime(atMs: Long): String = DateUtils.getRelativeTimeSpanString(
    atMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()
