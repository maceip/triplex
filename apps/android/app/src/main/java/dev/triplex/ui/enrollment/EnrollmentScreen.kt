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
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.triplexContentWidth
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.progress.Progress
import zed.rainxch.rikkaui.components.ui.progress.ProgressAnimation
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit,
    viewModel: EnrollmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val spacing = RikkaTheme.spacing

    LaunchedEffect(state.deviceReady) {
        if (state.deviceReady) onEnrollmentComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .triplexContentWidth()
            .padding(horizontal = TriplexLayout.screenHorizontal)
            .padding(top = spacing.xl, bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        TriplexReveal {
            TriplexStatusPill(
                text = "PHONE-FIRST · PRIVATE BY DESIGN",
                tone = TriplexCardTone.ACCENT
            )
        }

        TriplexReveal(delayMillis = TriplexLayout.staggerMillis) {
            Text(
                text = "TRIPLEX",
                variant = TextVariant.Large,
                color = RikkaTheme.colors.primary,
            )
            TriplexScreenHeader(
                eyebrow = "WELCOME",
                title = "Your voice agent starts here.",
                description = "Connect this phone once. The dialer works immediately; the agent line and voice lab unlock as you go."
            )
        }

        TriplexReveal(delayMillis = TriplexLayout.staggerMillis * 2) {
            TriplexCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg)
                ) {
                    Text("Secure setup", variant = TextVariant.H3)
                    Text(
                        "Your account links this device to its assigned number and encrypted local state. SIP credentials arrive when the Triplex line is provisioned.",
                        color = RikkaTheme.colors.onMuted
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
                                modifier = Modifier.padding(spacing.lg)
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

        TriplexReveal(delayMillis = TriplexLayout.staggerMillis * 3) {
            SetupProgress(state = state)
        }

        Spacer(Modifier.height(spacing.sm))
    }
}

@Composable
private fun SetupProgress(state: EnrollmentState) {
    val spacing = RikkaTheme.spacing
    val target = when {
        state.user != null && state.deviceReady -> 1f
        state.user != null -> 0.66f
        else -> 0.33f
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(RikkaTheme.motion.durationSlow),
        label = "Enrollment progress"
    )

    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
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
                trackColor = RikkaTheme.colors.border,
                fillColor = RikkaTheme.colors.primary,
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
        variant = TextVariant.Small,
        color = if (complete) RikkaTheme.colors.primary else RikkaTheme.colors.onMuted
    )
}
