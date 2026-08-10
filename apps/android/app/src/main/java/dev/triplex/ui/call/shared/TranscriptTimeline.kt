package dev.triplex.ui.call.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.SystemEvent
import dev.triplex.domain.call.SystemEventKind
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.call.UserReply
import dev.triplex.ui.components.chat.AgentChatTimeline
import dev.triplex.ui.components.chat.Speaker
import dev.triplex.ui.components.chat.TranscriptEntry

/**
 * Renders a call transcript, live or recorded.
 *
 * Typed on [TimelineEntry] — the domain shape both telephony stacks already
 * produce — rather than on the Room row, so the run-detail screen, the screening
 * surface (Phase 4) and the in-call agent panel (Phase 5) share one renderer
 * instead of three that drift. Persistence maps *into* this type; nothing here
 * knows a database exists.
 *
 * @param entries turns in chronological order.
 * @param autoScroll pull to the newest turn. On a live call, yes; on a finished
 *   run the user is reading, so the caller passes false.
 */
@Composable
fun TranscriptTimeline(
    entries: List<TimelineEntry>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
) {
    val bubbles = remember(entries) { entries.toTranscriptEntries() }
    AgentChatTimeline(entries = bubbles, modifier = modifier, autoScroll = autoScroll)
}

/**
 * Maps the domain timeline onto the chat component's model.
 *
 * Index-based keys: two turns can share a timestamp, and the position in the
 * timeline is the only thing guaranteed to be unique and stable.
 */
internal fun List<TimelineEntry>.toTranscriptEntries(): List<TranscriptEntry> =
    mapIndexedNotNull { index, entry ->
        when (entry) {
            is AgentUtterance -> TranscriptEntry(
                id = "$index",
                speaker = Speaker.AGENT,
                text = entry.text,
                timestamp = entry.atMs,
                isFinal = entry.complete,
            )

            is CallerUtterance -> TranscriptEntry(
                id = "$index",
                speaker = Speaker.CALLER,
                text = entry.text,
                timestamp = entry.atMs,
                isFinal = entry.final,
            )

            is UserReply -> TranscriptEntry(
                id = "$index",
                speaker = Speaker.USER,
                text = entry.text,
                timestamp = entry.atMs,
            )

            is SystemEvent -> TranscriptEntry(
                id = "$index",
                speaker = Speaker.SYSTEM,
                text = entry.kind.label(),
                timestamp = entry.atMs,
            )
        }
    }

private fun SystemEventKind.label(): String = when (this) {
    SystemEventKind.CALL_STARTED -> "Call started"
    SystemEventKind.AGENT_ATTACHED -> "Agent took the call"
    SystemEventKind.CALL_ENDED -> "Call ended"
}
