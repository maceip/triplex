package dev.triplex.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.components.OutlinedTextInput

/**
 * Consent-first voice cloning. The user reads one statement aloud; that
 * recording is both the consent record and the cloning reference. The screen
 * always shows where synthesis runs (placement) and offers revocation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCloneScreen(
    onBack: () -> Unit,
    viewModel: VoiceCloneViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var micGranted by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) viewModel.startRecording()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Voice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Read the sentence below aloud. That recording is both your " +
                    "consent and the sample used to create your voice.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card {
                Text(
                    CONSENT_STATEMENT,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic
                )
            }

            when (state.stage) {
                VoiceCloneStage.RECORDING -> Button(
                    onClick = { viewModel.stopRecording() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Stop recording") }

                VoiceCloneStage.PREPARING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing your voice…")
                }

                else -> Button(
                    onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy
                ) {
                    Text(if (state.hasProfile) "Re-record my voice" else "Record my voice")
                }
            }

            state.lastCaptureSeconds?.let { seconds ->
                val snr = state.captureSnrDb
                Text(
                    if (snr != null) {
                        "Last capture: %.1fs, signal-to-noise %.0f dB".format(seconds, snr)
                    } else {
                        "Last capture: %.1f seconds".format(seconds)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Feedback belongs next to the button that produced it: when a
            // voice already exists the rest of this screen is long enough to
            // push a message below the fold.
            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.hasProfile) {
                HorizontalDivider()
                Text("Speak something new", style = MaterialTheme.typography.titleMedium)
                OutlinedTextInput(
                    value = state.previewText,
                    onValueChange = viewModel::updatePreviewText,
                    label = "Text to speak in your voice",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.speakPreview() },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (state.stage) {
                                VoiceCloneStage.SYNTHESIZING -> "Synthesizing…"
                                VoiceCloneStage.PLAYING -> "Playing…"
                                else -> "Speak in my voice"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.playReference() },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Play my recording") }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Synthesis placement: ${state.placement}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (state.placementReason.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.placementReason,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        state.referenceSeconds?.let { seconds ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Consent recording: ${seconds}s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                TextButton(
                    onClick = { viewModel.revoke() },
                    enabled = !state.busy
                ) { Text("Delete my voice profile") }
            }

        }
    }
}
