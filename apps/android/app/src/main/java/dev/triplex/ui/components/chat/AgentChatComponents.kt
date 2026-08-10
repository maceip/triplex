package dev.triplex.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaui.components.ui.call.TranscriptText
import zed.rainxch.rikkaui.components.ui.call.transcriptTentative
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassSurface
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme
import dev.triplex.ui.theme.LiquidGlassDesign

enum class Speaker {
    AGENT,
    CALLER,
    USER,
    SYSTEM,
}

data class TranscriptEntry(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val timestamp: Long,
    /** False while ASR is still revising this turn; the bubble reads as provisional. */
    val isFinal: Boolean = true,
)

/**
 * The screening transcript, newest at the bottom.
 *
 * @param entries Turns in chronological order.
 * @param modifier [Modifier] applied to the list.
 * @param autoScroll Whether new turns pull the list to the bottom.
 */
@Composable
fun AgentChatTimeline(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
        contentPadding = PaddingValues(RikkaTheme.spacing.lg),
    ) {
        items(entries, key = { it.id }) { entry ->
            AgentChatMessage(entry = entry, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A single transcript turn.
 *
 * The bubble is glass tinted by speaker rather than a filled rectangle: these sit
 * over the live call backdrop, and an opaque bubble would punch a hole in it. The
 * owner's turns align right with a clipped top-right corner; everyone else aligns
 * left with the mirror of that.
 *
 * @param entry The turn to render.
 * @param modifier [Modifier] applied to the row the bubble sits in.
 */
@Composable
fun AgentChatMessage(
    entry: TranscriptEntry,
    modifier: Modifier = Modifier,
) {
    val roles = LiquidGlassDesign.colors

    val tint =
        when (entry.speaker) {
            Speaker.AGENT -> roles.timelineAgent
            Speaker.CALLER -> roles.timelineCaller
            Speaker.USER -> roles.timelineUser
            Speaker.SYSTEM -> RikkaTheme.colors.muted
        }

    val alignment =
        when (entry.speaker) {
            Speaker.USER -> Alignment.CenterEnd
            Speaker.SYSTEM -> Alignment.Center
            else -> Alignment.CenterStart
        }

    val shape =
        when (entry.speaker) {
            Speaker.USER ->
                RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            Speaker.SYSTEM -> RoundedCornerShape(16.dp)
            else ->
                RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        }

    Box(modifier = modifier, contentAlignment = alignment) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(fraction = 0.85f),
            level = GlassLevel.Subtle,
            shape = shape,
            tint = tint,
            contentPadding = PaddingValues(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs)) {
                if (entry.speaker != Speaker.SYSTEM) {
                    Text(text = entry.speaker.name, variant = TextVariant.Small, color = tint)
                }

                if (entry.isFinal) {
                    // Routed through TranscriptText rather than plain Text so any
                    // span the transcript carries (highlights, tentative runs) is
                    // painted by the call-transcript painters instead of dropping
                    // to flat glyphs.
                    val settled = remember(entry.text) { AnnotatedString(entry.text) }
                    TranscriptText(text = settled, variant = TextVariant.P)
                } else {
                    // An interim turn is still being revised. The animated
                    // squiggle says "still arriving" the way a static style
                    // cannot, and it stops the moment the turn is committed and
                    // this branch is dropped.
                    val provisional = remember(entry.text, entry.isFinal) {
                        buildAnnotatedString {
                            withStyle(transcriptTentative()) { append(entry.text) }
                        }
                    }
                    TranscriptText(text = provisional, variant = TextVariant.Muted)
                }

                if (entry.speaker != Speaker.SYSTEM) {
                    Text(text = formatTimestamp(entry.timestamp), variant = TextVariant.Muted)
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        else -> "${diff / 3_600_000}h ago"
    }
}
