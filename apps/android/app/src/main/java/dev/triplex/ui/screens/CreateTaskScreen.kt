package dev.triplex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.TaskType

@Composable
fun CreateTaskScreen(
    onTaskCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CreateTaskViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.createdTaskId) {
        state.createdTaskId?.let { onTaskCreated(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "New Task",
            style = MaterialTheme.typography.headlineMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaskType.values().forEach { type ->
                FilterChip(
                    selected = state.selectedType == type,
                    onClick = { viewModel.selectTaskType(type) },
                    label = { Text(type.name.replace("_", " ").lowercase().capitalize()) }
                )
            }
        }

        state.selectedType?.let { taskType ->
            TaskParameterInput(
                type = taskType,
                params = state.params,
                onParamChange = { key, value -> viewModel.updateParam(key, value) }
            )
        }

        OutlinedTextField(
            value = state.destinationNumber,
            onValueChange = { viewModel.updateDestinationNumber(it) },
            label = { Text("Destination Number") },
            placeholder = { Text("+1234567890") },
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = { viewModel.createTask() },
                enabled = !state.loading && state.selectedType != null && state.destinationNumber.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Create")
                }
            }
        }
    }
}

@Composable
fun TaskParameterInput(
    type: TaskType,
    params: Map<String, String>,
    onParamChange: (String, String) -> Unit
) {
    when (type) {
        TaskType.APPOINTMENT_MODIFY -> {
            OutlinedTextField(
                value = params["appointment_id"] ?: "",
                onValueChange = { onParamChange("appointment_id", it) },
                label = { Text("Appointment ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = params["new_date"] ?: "",
                onValueChange = { onParamChange("new_date", it) },
                label = { Text("New Date/Time") },
                placeholder = { Text("YYYY-MM-DD HH:MM") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = params["request"] ?: "",
                onValueChange = { onParamChange("request", it) },
                label = { Text("Modification Request") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        TaskType.RESERVATION_UPDATE -> {
            OutlinedTextField(
                value = params["reservation_id"] ?: "",
                onValueChange = { onParamChange("reservation_id", it) },
                label = { Text("Reservation ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = params["party_size"] ?: "",
                onValueChange = { onParamChange("party_size", it) },
                label = { Text("New Party Size") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = params["notes"] ?: "",
                onValueChange = { onParamChange("notes", it) },
                label = { Text("Additional Notes") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
