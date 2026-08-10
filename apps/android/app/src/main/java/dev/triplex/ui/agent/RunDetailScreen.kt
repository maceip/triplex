package dev.triplex.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.triplex.ui.theme.TriplexDesign
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold

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
    val spacing = TriplexDesign.spacing

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
                .padding(top = padding.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            val current = run
            if (current == null) {
                // Either the id is stale or the row was deleted; say so
                // rather than showing an empty transcript as if it were one.
                Text(
                    text = "This call is no longer on this device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.large,
                    ),
                )
                return@Column
            }

            TriplexCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
            ) {
                Column(
                    modifier = Modifier.padding(spacing.large),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    TriplexStatusPill(
                        text = current.outcomeLabel(),
                        tone = current.outcomeTone(),
                    )
                    Text(
                        text = current.counterpartNumber,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = relativeTime(current.startedAtMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (current.unfinished) {
                        Text(
                            text = "This call did not finish cleanly. Everything the " +
                                "agent recorded before it stopped is below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
