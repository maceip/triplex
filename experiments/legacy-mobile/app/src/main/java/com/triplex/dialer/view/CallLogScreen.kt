package com.triplex.dialer.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Call log screen — shows recent call history.
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        val calls = uiState.callLog

        if (calls.isEmpty()) {
            Box(modifier = Modifier.padding(insets).fillMaxSize()) {
                Text("No call history", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(insets)) {
                items(calls) { call ->
                    ListItem(
                        headlineContent = { Text(call.displayLabel()) },
                        supportingContent = {
                            Text("${call.state.name} · ${formatDuration(call.elapsedSeconds())}")
                        },
                        leadingContent = {
                            Icon(
                                if (call.isIncoming) Icons.AutoMirrored.Filled.CallReceived
                                else Icons.AutoMirrored.Filled.CallMade,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
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
