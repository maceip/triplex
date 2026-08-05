package com.triplex.dialer.view

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Voice Clone setup screen — the user records their voice so Triplex AI
 * can speak in their natural voice during calls.
 *
 * This is a marquee feature — needs to be beautiful, simple, and magical.
 *
 * Flow:
 * 1. Intro screen explaining voice clone
 * 2. Recording step — user reads a script, app captures audio
 * 3. Processing step — AI analyzes the voice sample
 * 4. Activation — toggle voice clone on/off
 * 5. Preview — listen to the cloned voice saying sample phrases
 *
 * The voice is used for both incoming and outgoing calls:
 * - Outgoing: AI calls a business, speaks in user's voice
 * - Incoming: AI answers a call, speaks in user's voice
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCloneSetup(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            // ─── Hero icon ───
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(40.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.RecordVoiceOver,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // ─── Title ───
            Text(
                text = "Your Voice, Powered by AI",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Triplex can speak in your natural voice during calls.\nRecord a sample and the AI will learn your voice.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // ─── How it works steps ───
            VoiceCloneSteps()

            Spacer(Modifier.weight(1f))

            // ─── Recording card ───
            RecordingCard()

            Spacer(Modifier.height(16.dp))

            // ─── Action buttons ───
            Button(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Record Voice Sample")
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSkip) {
                Text("Skip — use AI voice instead")
            }
        }
    }
}

@Composable
private fun VoiceCloneSteps() {
    val steps = listOf(
        "Read a short script aloud",
        "AI analyzes your voice pattern",
        "Triplex speaks in your voice on calls",
        "Toggle on/off anytime",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        steps.forEachIndexed { index, text ->
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Step number
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RecordingCard() {
    var recordingState by remember { mutableStateOf("ready") } // ready, recording, processing, done

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (recordingState) {
                "ready" -> {
                    Text(
                        text = "Read this script:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\"Hello, this is a test of my voice.\"",
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                "recording" -> {
                    Text(
                        text = "Recording... Speak now",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFF44336),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Animated recording indicator
                    Text(
                        text = "⬤",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color(0xFFF44336),
                    )
                }
                "processing" -> {
                    Text(
                        text = "Analyzing your voice...",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                "done" -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(32.dp),
                        contentDescription = "Complete",
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Voice cloned!",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF4CAF50),
                    )
                }
            }
        }
    }
}

/**
 * Toggle card shown after voice clone is active — allows user to
 * enable/disable voice clone for calls and preview it.
 */
@Composable
fun VoiceCloneToggle(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.RecordVoiceOver,
                    tint = if (isEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice Clone",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isEnabled) "Active — AI speaks in your voice"
                        else "Disabled — AI uses default voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                )
            }

            if (isEnabled) {
                Spacer(Modifier.height(12.dp))
                // Preview button
                Button(
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Preview your voice")
                }
            }
        }
    }
}
