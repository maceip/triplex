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
import kotlinx.coroutines.delay

/**
 * Chat-style bottom sheet showing Triplex call progress.
 *
 * Architecture:
 * - Top: header with company name and call phase
 * - Middle: scrollable transcript (AI ↔ business)
 * - Bottom: controls + status indicator
 *
 * Phases show distinct visual states:
 * - INTENT_COLLECT → info-gathering form
 * - DIALING → pulsing "calling..." animation
 * - SPEAKING → blue bubble with AI text
 * - LISTENING → green bubble with business text
 * - AWAITING_USER_INPUT → yank dialog overlay asking user for info
 * - SUCCESS → checkmark with outcome summary
 * - FAILURE → error with retry option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriplexCallChat(
    session: TriplexCallSession,
    onEndCall: () -> Unit,
    onRetry: () -> Unit,
    onYankResponse: (String) -> Unit,
    onYankCancel: () -> Unit,
    onStartCall: (TriplexCallSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // ─── Intent collection (initial phase) ───
            if (session.phase == TriplexCallPhase.INTENT_COLLECT) {
                IntentCollectSheet(
                    session = session,
                    onStartCall = onStartCall,
                    onCancel = onEndCall,
                )
                return@Column
            }

            // ─── Phase header ───
            PhaseHeader(session)

            Spacer(Modifier.height(8.dp))

            // ─── Transcript ───
            TranscriptList(session.transcript, modifier = Modifier.weight(1f))

            // ─── Outcome / Status ───
            OutcomeBanner(session)

            // ─── Yank dialog (when AI needs user input) ───
            if (session.phase == TriplexCallPhase.AWAITING_USER_INPUT) {
                UserYankDialog(
                    yankRequest = session.pendingYank,
                    onResponse = onYankResponse,
                    onCancel = onYankCancel,
                )
            }

            // ─── DTMF indicator ───
            session.pendingDTMF?.let { dtmf ->
                DTMFIndicator(dtmf)
            }

            // ─── Call controls ───
            CallControls(session.phase, onEndCall, onRetry)
        }
    }
}

@Composable
private fun PhaseHeader(session: TriplexCallSession) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phase icon
        Icon(
            imageVector = when (session.phase) {
                TriplexCallPhase.INTENT_COLLECT -> Icons.Filled.HelpOutline
                TriplexCallPhase.SCREENING -> Icons.Filled.FilterCenterFocus
                TriplexCallPhase.DIALING -> Icons.Filled.Smartphone
                TriplexCallPhase.SPEAKING -> Icons.Filled.RecordVoiceOver
                TriplexCallPhase.LISTENING -> Icons.Filled.Hearing
                TriplexCallPhase.AWAITING_USER_INPUT -> Icons.Filled.Person
                TriplexCallPhase.SUCCESS -> Icons.Filled.CheckCircle
                TriplexCallPhase.FAILURE -> Icons.Filled.Error
                TriplexCallPhase.CALLBACK_SCHEDULED -> Icons.Filled.Schedule
            },
            tint = when (session.phase) {
                TriplexCallPhase.SUCCESS -> Color(0xFF4CAF50)
                TriplexCallPhase.FAILURE -> Color(0xFFF44336)
                TriplexCallPhase.CALLBACK_SCHEDULED -> Color(0xFF2196F3)
                else -> MaterialTheme.colorScheme.primary
            },
            contentDescription = when (session.phase) {
                TriplexCallPhase.SUCCESS -> "Success"
                TriplexCallPhase.FAILURE -> "Failed"
                else -> "Phase icon"
            },
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))

        // Company name + phase text
        Column {
            Text(
                text = session.counterpartyName.ifBlank { "Triplex Support Call" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = phaseLabel(session.phase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))

        // Duration if call is active
        if (session.durationSec > 0) {
            Text(
                text = formatDuration(session.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptList(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        // Show placeholder when no transcript yet
        Box(
            modifier = modifier.padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        false -> "Ready to call..."
                        else -> "Enter what you need help with"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // System status — centered, italic, muted
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
            // Business bubble — left-aligned, green-ish
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
                    TriplexCallPhase.SUCCESS -> "✅ Success"
                    TriplexCallPhase.FAILURE -> "❌ Failed"
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
private fun CallControls(
    phase: TriplexCallPhase,
    onEndCall: () -> Unit,
    onRetry: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // End call — always visible
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFF44336),
                ),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "End call")
            }
            Text("End", fontSize = 11.sp)
        }

        // Retry — only on failure
        if (phase == TriplexCallPhase.FAILURE) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                }
                Text("Retry", fontSize = 11.sp)
            }
        }

        // Schedule callback — shown on callback state
        if (phase == TriplexCallPhase.CALLBACK_SCHEDULED) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = onEndCall) {
                    Icon(Icons.Filled.Check, contentDescription = "Done")
                }
                Text("Done", fontSize = 11.sp)
            }
        }
    }
}

/** Human-readable label for each phase. */
private fun phaseLabel(phase: TriplexCallPhase): String = when (phase) {
    TriplexCallPhase.INTENT_COLLECT -> "Tell us what you need"
    TriplexCallPhase.SCREENING -> "Screening incoming call..."
    TriplexCallPhase.DIALING -> "Calling..."
    TriplexCallPhase.SPEAKING -> "Speaking with support"
    TriplexCallPhase.LISTENING -> "Listening to response"
    TriplexCallPhase.AWAITING_USER_INPUT -> "We need your input"
    TriplexCallPhase.SUCCESS -> "Call completed"
    TriplexCallPhase.FAILURE -> "Call failed"
    TriplexCallPhase.CALLBACK_SCHEDULED -> "Will call back later"
}

/** Small indicator showing DTMF tones are being sent. */
@Composable
private fun DTMFIndicator(dtmf: DTMFSequence) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Dialpad,
                tint = Color(0xFFE65100),
                contentDescription = "DTMF tones",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Sending keys: ${dtmf.digits}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE65100),
            )
            if (dtmf.description.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "(${dtmf.description})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE65100).copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Format seconds into mm:ss. */
private fun formatDuration(sec: Int): String = when {
    sec < 60 -> "${sec}s"
    else -> "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}
