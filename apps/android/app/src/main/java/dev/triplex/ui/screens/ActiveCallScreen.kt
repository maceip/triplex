package dev.triplex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.nativebridge.agent.AgentState
import dev.triplex.telephony.sip.CallStateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    callViewModel: ActiveCallViewModel = hiltViewModel()
) {
    val callState by callViewModel.callState.collectAsState()
    val turnState by callViewModel.turnState.collectAsState()
    val epoch by callViewModel.currentEpoch.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        when (val state = callState) {
                            is CallStateInfo.Active -> "Active: ${state.destination}"
                            is CallStateInfo.Calling -> "Calling ${state.destination}"
                            else -> "Call"
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CallStatusCard(
                callState = callState,
                turnState = turnState,
                epoch = epoch
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { callViewModel.mute() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mute")
                }
                
                Button(
                    onClick = { callViewModel.interrupt() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Interrupt")
                }
            }
            
            FloatingActionButton(
                onClick = { callViewModel.hangup() },
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Icon(Icons.Default.Phone, contentDescription = "Hang Up")
            }
        }
    }
}

@Composable
fun CallStatusCard(
    callState: CallStateInfo,
    turnState: AgentState,
    epoch: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Turn State: ${turnState.name}",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Badge {
                    Text(epoch.toString())
                }
            }
            
            when (val state = callState) {
                is CallStateInfo.Active -> {
                    Text(
                        text = "Destination: ${state.destination}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Started: ${formatTime(state.startTime)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is CallStateInfo.Calling -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {}
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val elapsed = (System.currentTimeMillis() - timestamp) / 1000
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    return String.format("%02d:%02d", minutes, seconds)
}
