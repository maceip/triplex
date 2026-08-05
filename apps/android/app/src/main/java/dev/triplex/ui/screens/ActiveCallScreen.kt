package dev.triplex.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.nativebridge.agent.AgentState
import dev.triplex.telephony.sip.CallStateInfo
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexSignalOrb
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexDesign
import java.util.Locale

@Composable
fun ActiveCallScreen(
    callViewModel: ActiveCallViewModel = hiltViewModel()
) {
    val callState by callViewModel.callState.collectAsState()
    val turnState by callViewModel.turnState.collectAsState()
    val epoch by callViewModel.currentEpoch.collectAsState()
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion

    TriplexBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TriplexTopBar(title = callTitle(callState))
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.screenHorizontal)
                    .padding(top = spacing.large, bottom = spacing.xLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xLarge)
            ) {
                TriplexReveal {
                    TriplexSignalOrb(
                        active = callState is CallStateInfo.Active,
                        modifier = Modifier.size(124.dp)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                AnimatedContent(
                    targetState = turnState,
                    transitionSpec = {
                        (fadeIn(tween(motion.standardMillis)) + scaleIn(initialScale = 0.96f)) togetherWith
                            (fadeOut(tween(motion.quickMillis)) + scaleOut(targetScale = 0.98f))
                    },
                    label = "Call turn state"
                ) { state ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = turnHeadline(state),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = turnDescription(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                CallStatusCard(
                    callState = callState,
                    turnState = turnState,
                    epoch = epoch
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium)
                ) {
                    TriplexButton(
                        text = "Mute",
                        onClick = callViewModel::mute,
                        style = TriplexButtonStyle.OUTLINE,
                        modifier = Modifier.weight(1f)
                    )
                    TriplexButton(
                        text = "Interrupt",
                        onClick = callViewModel::interrupt,
                        style = TriplexButtonStyle.SECONDARY,
                        modifier = Modifier.weight(1f)
                    )
                }

                TriplexButton(
                    text = "End call",
                    onClick = callViewModel::hangup,
                    style = TriplexButtonStyle.DANGER,
                    leadingIcon = Icons.Default.Phone,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun CallStatusCard(
    callState: CallStateInfo,
    turnState: AgentState,
    epoch: Long,
    modifier: Modifier = Modifier
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(
        modifier = modifier.fillMaxWidth(),
        tone = TriplexCardTone.ACCENT
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TriplexStatusPill(
                    text = turnState.name.lowercase().replaceFirstChar { it.titlecase() },
                    tone = when (turnState) {
                        AgentState.LISTENING -> TriplexCardTone.SUCCESS
                        AgentState.YIELDING -> TriplexCardTone.WARNING
                        else -> TriplexCardTone.DEFAULT
                    },
                    leadingColor = when (turnState) {
                        AgentState.LISTENING -> TriplexDesign.colors.success
                        AgentState.YIELDING -> TriplexDesign.colors.warning
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = "EPOCH $epoch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.62f)
                )
            }

            when (val state = callState) {
                is CallStateInfo.Active -> {
                    Text(state.destination, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Connected · ${formatTime(state.startTime)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
                is CallStateInfo.Calling -> {
                    Text(state.destination, style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> Text(
                    "Waiting for call state",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun callTitle(callState: CallStateInfo): String = when (callState) {
    is CallStateInfo.Active -> "Live with ${callState.destination}"
    is CallStateInfo.Calling -> "Calling ${callState.destination}"
    else -> "Call"
}

private fun turnHeadline(state: AgentState): String = when (state) {
    AgentState.STOPPED -> "Standing by"
    AgentState.LISTENING -> "Listening"
    AgentState.ENDPOINTING -> "Finishing the caller turn"
    AgentState.COMMITTED -> "Thinking"
    AgentState.SPEAKING -> "Speaking"
    AgentState.YIELDING -> "Yielding"
    AgentState.ENDED -> "Call ended"
}

private fun turnDescription(state: AgentState): String = when (state) {
    AgentState.STOPPED -> "The phone runtime is waiting for a call"
    AgentState.LISTENING -> "The native turn detector is receiving the current capture stream"
    AgentState.ENDPOINTING -> "Adaptive endpointing is deciding when the caller finished"
    AgentState.COMMITTED -> "The caller turn is committed for one bounded response"
    AgentState.SPEAKING -> "The native runtime is emitting the current response"
    AgentState.YIELDING -> "Stale speech was cancelled and flushed after interruption"
    AgentState.ENDED -> "The native turn session has ended cleanly"
}

private fun formatTime(timestamp: Long): String {
    val elapsed = (System.currentTimeMillis() - timestamp) / 1000
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
