package dev.triplex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.ui.components.OutlinedTextInput

@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit,
    viewModel: EnrollmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.deviceReady) {
        if (state.deviceReady) {
            onEnrollmentComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Set Up Triplex",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Register your account and device to enable phone-based voice tasks.",
            style = MaterialTheme.typography.bodyLarge
        )

        OutlinedTextInput(
            label = "Email",
            value = state.email,
            onValueChange = { viewModel.updateEmail(it) },
            enabled = !state.loading && state.user == null
        )

        OutlinedTextInput(
            label = "Phone Number",
            value = state.phoneNumber,
            onValueChange = { viewModel.updatePhoneNumber(it) },
            placeholder = "+1234567890",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = !state.loading && state.user == null
        )

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.registerAndSetup() },
            enabled = !state.loading && state.email.isNotBlank() && state.phoneNumber.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Register & Setup Device")
            }
        }

        LinearProgressIndicator(
            progress = {
                when {
                    state.user != null && state.deviceReady -> 1.0f
                    state.user != null -> 0.66f
                    else -> 0.33f
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Account",
                style = if (state.user != null) {
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                }
            )
            Text(
                text = "Device",
                style = if (state.device != null) {
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                }
            )
            Text(
                text = "Ready",
                style = if (state.deviceReady) {
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                }
            )
        }
    }
}
