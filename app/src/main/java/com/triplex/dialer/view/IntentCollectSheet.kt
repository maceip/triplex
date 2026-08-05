package com.triplex.dialer.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.triplex.dialer.model.TriplexCallSession
import com.triplex.dialer.model.TriplexCallPhase

/**
 * Intent Collection UI — the user tells Triplex what they need before a call.
 *
 * Bounded to Samsung support initially.
 *
 * Fields:
 * - Company (Samsung pre-selected)
 * - Issue description (free text)
 * - Expected outcome (what "success" looks like)
 * - Known info (order number, serial, etc.)
 *
 * When the user submits, Triplex dials the business and handles the call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentCollectSheet(
    session: TriplexCallSession,
    onStartCall: (TriplexCallSession) -> Unit,
    onCancel: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // ─── Header ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.HelpOutline,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "What do you need help with?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─── Company selector (bounded to Samsung) ───
            Text(
                text = "Company",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            var selectedCompany by remember { mutableStateOf("Samsung") }
            val companies = listOf("Samsung", "Apple", "Amazon", "Other")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                companies.forEach { company ->
                    FilterChip(
                        selected = selectedCompany == company,
                        onClick = { selectedCompany = company },
                        label = { Text(company) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ─── Issue description ───
            var issueText by remember { mutableStateOf("") }
            Text(
                text = "What's the issue?",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = issueText,
                onValueChange = { issueText = it },
                placeholder = { Text("e.g. TV order hasn't arrived") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            Spacer(Modifier.height(12.dp))

            // ─── Expected outcome ───
            var outcomeText by remember { mutableStateOf("") }
            Text(
                text = "What would success look like?",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = outcomeText,
                onValueChange = { outcomeText = it },
                placeholder = { Text("e.g. Get tracking number and delivery date") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            Spacer(Modifier.height(12.dp))

            // ─── Known info (optional) ───
            var knownInfo by remember { mutableStateOf("") }
            Text(
                text = "Any info you already have (optional)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = knownInfo,
                onValueChange = { knownInfo = it },
                placeholder = { Text("e.g. Order number SAM-1234-5678") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            // ─── Actions ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val updated = session.copy(
                            phase = TriplexCallPhase.DIALING,
                            counterpartyName = selectedCompany,
                            issueDescription = issueText,
                            expectedOutcome = outcomeText,
                        )
                        onStartCall(updated)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = issueText.isNotBlank() && outcomeText.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Call")
                }
            }
        }
    }
}
