package com.triplex.dialer.view

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.triplex.dialer.model.*

/**
 * Full-screen incoming call handler — like Google Call Screen but for resolution.
 *
 * When someone calls the user, Triplex AI answers and talks to them.
 * The user watches the transcript in real-time and sees the outcome.
 *
 * Architecture:
 * - Top: caller info + AI status
 * - Middle: scrollable transcript (AI ↔ caller)
 * - Bottom: controls (transfer to user, end call)
 *
 * Yank: if AI needs user input (e.g., confirm a payment date), the yank
 * dialog appears as an overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(
    session: TriplexCallSession,
    onTransferToUser: () -> Unit,
    onEndCall: () -> Unit,
    onYankResponse: (String) -> Unit,
    onYankCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            // ─── Top: caller info ───
            CallerInfoHeader(session)

            Spacer(Modifier.height(8.dp))

            // ─── Transcript ───
            TranscriptList(session.transcript, modifier = Modifier.weight(1f))

            // ─── Outcome banner ───
            OutcomeBanner(session)

            // ─── Yank dialog ───
            if (session.phase == TriplexCallPhase.AWAITING_USER_INPUT) {
                Spacer(Modifier.height(8.dp))
                UserYankDialog(
                    yankRequest = session.pendingYank,
                    onResponse = onYankResponse,
                    onCancel = onYankCancel,
                )
            }

            // ─── Controls ───
            IncomingControls(session.phase, onTransferToUser, onEndCall)
        }
    }
}

@Composable
private fun CallerInfoHeader(session: TriplexCallSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar placeholder
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.counterpartyName.ifBlank { "Unknown Caller" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = aiStatusLabel(session.phase),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // Duration
            if (session.durationSec > 0) {
                Text(
                    text = formatDuration(session.durationSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TranscriptList(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Triplex AI is answering...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                // Pulsing dots animation placeholder
                Text(
                    text = "···",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(vertical = 4.dp),
        ) {
            items(entries) { entry ->
                TranscriptBubble(entry)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val isAi = entry.speaker == TranscriptSpeaker.AI_AGENT
    val isBusiness = entry.speaker == TranscriptSpeaker.BUSINESS
    val isSystem = entry.speaker == TranscriptSpeaker.SYSTEM

    when {
        isSystem -> {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        isAi -> {
            // AI bubble — right-aligned, blue-ish
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp),
                    color = Color(0xFFE3F2FD),
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        isBusiness -> {
            // Caller bubble — left-aligned, green-ish
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        else -> {
            // User message — left-aligned, gray
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OutcomeBanner(session: TriplexCallSession) {
    val outcome = session.outcome ?: return

    Spacer(Modifier.height(8.dp))

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (session.phase) {
                TriplexCallPhase.SUCCESS -> Color(0xFFE8F5E9)
                TriplexCallPhase.FAILURE -> Color(0xFFFFEBEE)
                TriplexCallPhase.CALLBACK_SCHEDULED -> Color(0xFFE3F2FD)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when (session.phase) {
                    TriplexCallPhase.SUCCESS -> "✅ Resolved"
                    TriplexCallPhase.FAILURE -> "❌ Could not resolve"
                    TriplexCallPhase.CALLBACK_SCHEDULED -> "📅 Callback Scheduled"
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = outcome.summary,
                style = MaterialTheme.typography.bodySmall,
            )
            outcome.referenceNumber?.let {
                Text(
                    text = "Ref: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            outcome.nextAction?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Next: $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun IncomingControls(
    phase: TriplexCallPhase,
    onTransferToUser: () -> Unit,
    onEndCall: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Transfer to user
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onTransferToUser,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF2196F3),
                ),
            ) {
                Icon(Icons.Filled.Phone, contentDescription = "Pick up")
            }
            Text("Pick up", fontSize = 11.sp)
        }

        // End call
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFF44336),
                ),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "End")
            }
            Text("End", fontSize = 11.sp)
        }
    }
}

/** Human-readable status label for the AI during an incoming call. */
private fun aiStatusLabel(phase: TriplexCallPhase): String = when (phase) {
    TriplexCallPhase.SCREENING -> "Triplex AI is screening this call..."
    TriplexCallPhase.SPEAKING -> "Triplex AI is speaking"
    TriplexCallPhase.LISTENING -> "Triplex AI is listening"
    TriplexCallPhase.AWAITING_USER_INPUT -> "Triplex AI needs your input"
    TriplexCallPhase.SUCCESS -> "Call resolved by Triplex AI"
    TriplexCallPhase.FAILURE -> "Triplex AI could not resolve"
    TriplexCallPhase.CALLBACK_SCHEDULED -> "Triplex AI scheduled a callback"
    else -> "Triplex AI"
}

/** Format seconds into mm:ss. */
private fun formatDuration(sec: Int): String = when {
    sec < 60 -> "${sec}s"
    else -> "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}
