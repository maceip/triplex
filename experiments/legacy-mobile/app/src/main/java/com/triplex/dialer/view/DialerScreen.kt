package com.triplex.dialer.view

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.triplex.dialer.model.DialerUiState
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Main dialer screen — number pad, call log shortcut, settings.
 *
 * Business owner flow: dial → call → in-call UX.
 * Triplex agent flow: select agent → dial out → in-call UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(
    viewModel: DialerViewModel,
    onCall: (String) -> Unit,
    onCallLog: () -> Unit,
    onSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Triplex Dialer") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = onCallLog,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                        ),
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Call Log")
                    }
                }
            }
        },
    ) { padding ->
        DialerContent(
            uiState = uiState,
            onDialDigit = viewModel::onDialDigit,
            onDialBackspace = viewModel::onDialBackspace,
            onPlaceCall = { viewModel.placeCall(onCall) },
            onTriplexAgentCall = { viewModel.placeTriplexAgentCall(onCall) },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DialerContent(
    uiState: DialerUiState,
    onDialDigit: (Char) -> Unit,
    onDialBackspace: () -> Unit,
    onPlaceCall: () -> Unit,
    onTriplexAgentCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Dialed number display
        Text(
            text = uiState.dialInput.ifBlank { "Enter number" },
            fontSize = 32.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(16.dp))

        // Triplex ready indicator
        if (uiState.triplexReady) {
            AssistChip(
                onClick = { },
                label = { Text("Triplex Voice Engine Ready") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                ),
            )
        }

        Spacer(Modifier.height(24.dp))

        // Number pad
        NumberPad(
            onDigit = onDialDigit,
            onBackspace = onDialBackspace,
        )

        Spacer(Modifier.height(24.dp))

        // Call button — two variants
        Button(
            onClick = onPlaceCall,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Call")
        }

        Spacer(Modifier.height(8.dp))

        // Triplex agent call button (web-human dial-out)
        OutlinedButton(
            onClick = onTriplexAgentCall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.SmartToy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Triplex Agent Call")
        }
    }
}

@Composable
private fun NumberPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (label in row) {
                    val char = label[0]
                    FilledIconButton(
                        onClick = { onDigit(char) },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Text(label, fontSize = 24.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        IconButton(onClick = onBackspace) {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
        }
    }
}
