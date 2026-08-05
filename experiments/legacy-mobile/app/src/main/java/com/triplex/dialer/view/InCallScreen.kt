package com.triplex.dialer.view

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.triplex.dialer.model.*
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * In-call screen — the core user experience during a live call.
 *
 * Shows:
 * - Call status (duration, participant)
 * - Triplex capability indicators (echo cancel, Chime, interruption, voice clone)
 * - Call controls (mute, speaker, hold, end)
 * - Triplex agent status if this is an agent-mediated call
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InCallScreen(
    callId: String,
    viewModel: DialerViewModel,
    onEndCall: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val caps by viewModel.capabilities.collectAsState()

    val call = uiState.activeCalls.find { it.id == callId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(call?.displayLabel() ?: "Call", fontSize = 18.sp)
                        if (call?.isTriplexAgent == true) {
                            Text("Triplex Agent", fontSize = 14.sp, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onEndCall) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "End call")
                    }
                },
            )
        },
    ) { padding ->
        InCallContent(
            call = call,
            caps = caps,
            isMuted = uiState.isMicMuted,
            isSpeakerOn = uiState.isSpeakerOn,
            onToggleMute = viewModel::toggleMute,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onEndCall = onEndCall,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun InCallContent(
    call: CallData?,
    caps: TriplexCallCapabilities,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Triplex session — null = no Triplex call active
    // Starts as INTENT_COLLECT when user initiates Triplex support
    var triplexSession by remember { mutableStateOf<TriplexCallSession?>(null) }

    // Show Triplex call button if no session active
    val showTriplexButton = triplexSession == null && call?.isTriplexAgent == true

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        // ─── Main call controls (top) ───
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Call status
            if (call != null) {
                Text(
                    text = formatDuration(call.elapsedSeconds()),
                    fontSize = 40.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = call.state.name,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(24.dp))

            // Triplex Capability Dashboard
            CapabilityDashboard(caps)

            Spacer(Modifier.height(24.dp))

            // Call controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // Mute
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onToggleMute,
                        colors = if (isMuted) IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ) else IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(
                            if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Mute",
                        )
                    }
                    Text("Mute", fontSize = 12.sp)
                }

                // Speaker
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = onToggleSpeaker) {
                        Icon(
                            if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = "Speaker",
                        )
                    }
                    Text("Speaker", fontSize = 12.sp)
                }

                // End call
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onEndCall,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "End")
                    }
                    Text("End", fontSize = 12.sp)
                }
            }

            // ─── Triplex support button (only when no session active) ───
            if (showTriplexButton) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        triplexSession = TriplexCallSession(
                            phase = TriplexCallPhase.INTENT_COLLECT,
                            counterpartyName = "Samsung Support",
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
                    ),
                ) {
                    Icon(Icons.Filled.SmartToy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Triplex Support Call")
                }
            }
        }

        // ─── Triplex call chat (bottom sheet overlay) ───
        triplexSession?.let { session ->
            TriplexCallChat(
                session = session,
                onEndCall = {
                    triplexSession = null
                    onEndCall()
                },
                onRetry = {
                    triplexSession = triplexSession?.copy(phase = TriplexCallPhase.DIALING)
                },
                onYankResponse = { answer ->
                    triplexSession = triplexSession?.copy(
                        phase = TriplexCallPhase.SPEAKING,
                        pendingYank = null,
                    )
                },
                onYankCancel = {
                    triplexSession = triplexSession?.copy(
                        phase = TriplexCallPhase.SPEAKING,
                        pendingYank = null,
                    )
                },
                onStartCall = { updated ->
                    triplexSession = updated.copy(phase = TriplexCallPhase.DIALING)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 500.dp),
            )
        }
    }
}

@Composable
private fun CapabilityDashboard(caps: TriplexCallCapabilities) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Triplex Capabilities", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            // Echo cancellation
            CapabilityRow(
                label = "Echo Cancellation",
                icon = Icons.Filled.Hearing,
                status = caps.echoCancellation.statusText(),
                statusColor = caps.echoCancellation.statusColor(),
            )

            // Chime connection
            CapabilityRow(
                label = "Chime Meeting",
                icon = Icons.Filled.Wifi,
                status = caps.chimeConnection.statusText(),
                statusColor = caps.chimeConnection.statusColor(),
            )

            // Interruption (barge-in)
            CapabilityRow(
                label = "Barge-In",
                icon = Icons.Filled.SwapHoriz,
                status = caps.interruption.statusText(),
                statusColor = caps.interruption.statusColor(),
            )

            // Voice clone
            if (caps.voiceClone != null) {
                CapabilityRow(
                    label = "Voice Clone",
                    icon = Icons.Filled.VoiceOverOff,
                    status = caps.voiceClone.displayName,
                    statusColor = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Pipeline latency
            if (caps.pipelineLatencyMs > 0) {
                Text(
                    text = "Pipeline: ${caps.pipelineLatencyMs}ms",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    status: String,
    statusColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = statusColor)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(status, fontSize = 12.sp, color = statusColor)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun EchoCancellationState.statusText() = when (this) {
    EchoCancellationState.ACTIVE -> "Active"
    EchoCancellationState.CALIBRATING -> "Calibrating"
    EchoCancellationState.BYPASSED -> "Bypassed"
    EchoCancellationState.ERROR -> "Error"
}

@Composable
private fun EchoCancellationState.statusColor() = when (this) {
    EchoCancellationState.ACTIVE -> MaterialTheme.colorScheme.primary
    EchoCancellationState.CALIBRATING -> MaterialTheme.colorScheme.tertiary
    EchoCancellationState.BYPASSED -> MaterialTheme.colorScheme.secondary
    EchoCancellationState.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun ChimeConnectionState.statusText() = when (this) {
    ChimeConnectionState.DISCONNECTED -> "Disconnected"
    ChimeConnectionState.CONNECTING -> "Connecting"
    ChimeConnectionState.CONNECTED -> "Connected"
    ChimeConnectionState.RECONNECTING -> "Reconnecting"
    ChimeConnectionState.ERROR -> "Error"
}

@Composable
private fun ChimeConnectionState.statusColor() = when (this) {
    ChimeConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    ChimeConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
    ChimeConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    ChimeConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiary
    ChimeConnectionState.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun InterruptionState.statusText() = when (this) {
    InterruptionState.IDLE -> "Idle"
    InterruptionState.CALLER_SPEECH_DETECTED -> "Speech Detected"
    InterruptionState.TEARING_DOWN -> "Tearing Down"
    InterruptionState.TEARDOWN_COMPLETE -> "Ready"
    InterruptionState.SUPPRESSED -> "Suppressed"
}

@Composable
private fun InterruptionState.statusColor() = when (this) {
    InterruptionState.IDLE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    InterruptionState.CALLER_SPEECH_DETECTED -> MaterialTheme.colorScheme.tertiary
    InterruptionState.TEARING_DOWN -> MaterialTheme.colorScheme.error
    InterruptionState.TEARDOWN_COMPLETE -> MaterialTheme.colorScheme.primary
    InterruptionState.SUPPRESSED -> MaterialTheme.colorScheme.error
}
