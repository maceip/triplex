package com.triplex.dialer.view

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Settings screen — Triplex engine configuration.
 *
 * Echo delay calibration, voice clone profile management,
 * Chime meeting region, interruption sensitivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DialerViewModel,
    onBack: () -> Unit,
    onVoiceCloneSetup: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Echo delay calibration
            Text("Echo Cancellation", style = MaterialTheme.typography.titleMedium)
            Text(
                "Echo delay: ${viewModel.echoDelayMs}ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            // Voice cloning section
            Text("Voice Cloning", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Clone profiles: ${viewModel.cloneProfiles.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onVoiceCloneSetup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Set up Voice Clone")
            }

            Spacer(Modifier.height(16.dp))

            // Chime section
            Text("AWS Chime SDK", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Media region: auto (us-east-1)",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            // Interruption section
            Text("Barge-In (Interruption)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Teardown deadline: ${viewModel.interruptionDeadlineMs}ms",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))

            // About
            Text("Triplex Dialer v0.1.0", style = MaterialTheme.typography.bodySmall)
            Text(
                "Four-paper capability extensions for Chime voice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
