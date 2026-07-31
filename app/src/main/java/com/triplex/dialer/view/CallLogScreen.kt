package com.triplex.dialer.view

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Call log screen — shows recent call history.
 * Mirrors AndroidX reference app's CallLogScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogScreen(
    viewModel: DialerViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val calls = uiState.callLog

        if (calls.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text("No call history", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(calls) { call ->
                    ListItem(
                        headlineContent = {
                            Text(call.displayLabel())
                        },
                        supportingContent = {
                            Text("${call.state.name} · ${formatDuration(call.elapsedSeconds())}")
                        },
                        leadingIcon = {
                            Icon(
                                if (call.isIncoming) Icons.Filled.CallReceived
                                else Icons.Filled.CallMade,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (call.isTriplexAgent) {
                                Icon(Icons.Filled.SmartToy, contentDescription = "Triplex Agent")
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
