package dev.triplex.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.call.shared.TranscriptTimeline
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.triplexContentWidth
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * `agent/run/{runId}` — one recorded call, in full (reskin.md §3.2).
 *
 * The transcript is rendered by the shared
 * [dev.triplex.ui.call.shared.TranscriptTimeline], the same component the live
 * call surfaces use, so a recorded call and a live one read identically.
 */
@Composable
fun RunDetailScreen(
    onBack: () -> Unit,
    viewModel: RunDetailViewModel = hiltViewModel(),
) {
    val run by viewModel.run.collectAsState()
    val spacing = RikkaTheme.spacing

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = run?.counterpartName ?: "Call",
                navigationIcon = RikkaIcons.ArrowLeft,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .triplexContentWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            val current = run
            if (current == null) {
                // Either the id is stale or the row was deleted; say so
                // rather than showing an empty transcript as if it were one.
                Text(
                    text = "This call is no longer on this device.",
                    variant = TextVariant.Large,
                    color = RikkaTheme.colors.onMuted,
                    modifier = Modifier.padding(
                        horizontal = TriplexLayout.screenHorizontal,
                        vertical = spacing.lg,
                    ),
                )
                return@Column
            }

            TriplexCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TriplexLayout.screenHorizontal),
            ) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    TriplexStatusPill(
                        text = current.outcomeLabel(),
                        tone = current.outcomeTone(),
                    )
                    Text(text = current.counterpartNumber)
                    Text(
                        text = relativeTime(current.startedAtMs),
                        variant = TextVariant.Small,
                        color = RikkaTheme.colors.onMuted,
                    )
                    if (current.unfinished) {
                        Text(
                            text = "This call did not finish cleanly. Everything the " +
                                "agent recorded before it stopped is below.",
                            variant = TextVariant.Small,
                            color = RikkaTheme.colors.onMuted,
                        )
                    }
                }
            }

            TranscriptTimeline(
                entries = current.timeline,
                modifier = Modifier.fillMaxSize(),
                // A finished call is read, not followed.
                autoScroll = current.unfinished,
            )
        }
    }
}
