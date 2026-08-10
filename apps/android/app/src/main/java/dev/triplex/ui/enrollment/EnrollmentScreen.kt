package dev.triplex.ui.enrollment

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.EnrollmentState
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.theme.TriplexDesign
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.progress.Progress
import zed.rainxch.rikkaui.components.ui.progress.ProgressAnimation

@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit,
    viewModel: EnrollmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val spacing = TriplexDesign.spacing

    LaunchedEffect(state.deviceReady) {
        if (state.deviceReady) onEnrollmentComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
            .padding(top = spacing.xLarge, bottom = spacing.xxLarge),
        verticalArrangement = Arrangement.spacedBy(spacing.xLarge)
    ) {
        TriplexReveal {
            TriplexStatusPill(
                text = "PHONE-FIRST · PRIVATE BY DESIGN",
                tone = TriplexCardTone.ACCENT
            )
        }

        TriplexReveal(delayMillis = TriplexDesign.motion.staggerMillis) {
            TriplexScreenHeader(
                eyebrow = "WELCOME TO TRIPLEX",
                title = "Your voice agent starts here.",
                description = "Connect this phone once. Triplex stays ready for the bounded calls you approve."
            )
        }

        TriplexReveal(delayMillis = TriplexDesign.motion.staggerMillis * 2) {
            TriplexCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.xLarge),
                    verticalArrangement = Arrangement.spacedBy(spacing.large)
                ) {
                    Text("Secure setup", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your account links this device to its assigned number and encrypted local state.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextInput(
                        label = "Email",
                        value = state.email,
                        onValueChange = viewModel::updateEmail,
                        enabled = !state.loading && state.user == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextInput(
                        label = "Phone number",
                        value = state.phoneNumber,
                        onValueChange = viewModel::updatePhoneNumber,
                        placeholder = "+1 415 555 0100",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        enabled = !state.loading && state.user == null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    state.error?.let { error ->
                        TriplexCard(
                            modifier = Modifier.fillMaxWidth(),
                            tone = TriplexCardTone.DANGER
                        ) {
                            Text(
                                text = error,
                                modifier = Modifier.padding(spacing.large),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    TriplexButton(
                        text = "Register this device",
                        onClick = viewModel::registerAndSetup,
                        enabled = state.email.isNotBlank() && state.phoneNumber.isNotBlank(),
                        loading = state.loading,
                        leadingIcon = RikkaIcons.Phone,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        TriplexReveal(delayMillis = TriplexDesign.motion.staggerMillis * 3) {
            SetupProgress(state = state)
        }

        Spacer(Modifier.height(spacing.small))
    }
}

@Composable
private fun SetupProgress(state: EnrollmentState) {
    val spacing = TriplexDesign.spacing
    val target = when {
        state.user != null && state.deviceReady -> 1f
        state.user != null -> 0.66f
        else -> 0.33f
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(TriplexDesign.motion.expressiveMillis),
        label = "Enrollment progress"
    )

    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SetupLabel("Account", complete = state.user != null)
                SetupLabel("Device", complete = state.device != null || state.user != null)
                SetupLabel("Ready", complete = state.deviceReady)
            }
            Progress(
                progress = progress,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                fillColor = MaterialTheme.colorScheme.primary,
                height = 6.dp,
                // The value handed in is already tweened above; a second spring
                // on top of it would drag the bar behind the step labels.
                animation = ProgressAnimation.None
            )
        }
    }
}

@Composable
private fun SetupLabel(label: String, complete: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (complete) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
        }
    )
}
