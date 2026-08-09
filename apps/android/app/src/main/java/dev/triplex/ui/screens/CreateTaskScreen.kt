package dev.triplex.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.TaskType
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexDesign

@Composable
fun CreateTaskScreen(
    onTaskCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CreateTaskViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion

    LaunchedEffect(state.createdTaskId) {
        state.createdTaskId?.let(onTaskCreated)
    }

    TriplexBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TriplexTopBar(
                    title = "New automation",
                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavigationClick = onDismiss
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal)
                    .padding(top = spacing.large, bottom = spacing.xxLarge),
                verticalArrangement = Arrangement.spacedBy(spacing.xLarge)
            ) {
                TriplexReveal {
                    TriplexScreenHeader(
                        eyebrow = "OUTBOUND AUTOMATION",
                        title = "What can Triplex take off your plate?",
                        description = "Start with a specific, reviewable job. Triplex calls in your voice and stays inside the limits you set."
                    )
                }

                TriplexReveal(delayMillis = motion.staggerMillis) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(spacing.medium)
                    ) {
                        TaskTypeCard(
                            title = AutomationCatalog.ReturnSamsungItem.title,
                            description = AutomationCatalog.ReturnSamsungItem.description,
                            selected = state.selectedType == TaskType.ITEM_RETURN,
                            onClick = { viewModel.selectTaskType(TaskType.ITEM_RETURN) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.medium)
                        ) {
                        TaskTypeCard(
                            title = "Appointment",
                            description = "Move or update a scheduled visit",
                            selected = state.selectedType == TaskType.APPOINTMENT_MODIFY,
                            onClick = { viewModel.selectTaskType(TaskType.APPOINTMENT_MODIFY) },
                            modifier = Modifier.weight(1f)
                        )
                        TaskTypeCard(
                            title = "Reservation",
                            description = "Change a booking or party details",
                            selected = state.selectedType == TaskType.RESERVATION_UPDATE,
                            onClick = { viewModel.selectTaskType(TaskType.RESERVATION_UPDATE) },
                            modifier = Modifier.weight(1f)
                        )
                        }
                    }
                }

                AnimatedContent(
                    targetState = state.selectedType,
                    transitionSpec = {
                        (fadeIn(tween(motion.standardMillis)) +
                            slideInVertically(tween(motion.standardMillis)) { it / 10 }) togetherWith
                            (fadeOut(tween(motion.quickMillis)) +
                                slideOutVertically(tween(motion.quickMillis)) { -it / 12 })
                    },
                    label = "Task form"
                ) { taskType ->
                    if (taskType == null) {
                        TriplexCard(
                            modifier = Modifier.fillMaxWidth(),
                            tone = TriplexCardTone.DEFAULT
                        ) {
                            Text(
                                "Select a task above to reveal only the fields Triplex needs.",
                                modifier = Modifier.padding(spacing.xLarge),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        TriplexCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(spacing.xLarge),
                                verticalArrangement = Arrangement.spacedBy(spacing.large)
                            ) {
                                Text("Automation details", style = MaterialTheme.typography.titleLarge)
                                TaskParameterInput(
                                    type = taskType,
                                    params = state.params,
                                    onParamChange = viewModel::updateParam
                                )
                                OutlinedTextInput(
                                    value = state.destinationNumber,
                                    onValueChange = viewModel::updateDestinationNumber,
                                    label = "Destination number",
                                    placeholder = "+1 415 555 0100",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                state.error?.let { error ->
                    TriplexCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = TriplexCardTone.DANGER
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium)
                ) {
                    TriplexButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        style = TriplexButtonStyle.OUTLINE,
                        modifier = Modifier.weight(1f)
                    )
                    TriplexButton(
                        text = "Create automation",
                        onClick = viewModel::createTask,
                        enabled = state.selectedType != null && state.destinationNumber.isNotBlank(),
                        loading = state.loading,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskTypeCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(
        modifier = modifier,
        tone = if (selected) TriplexCardTone.ACCENT else TriplexCardTone.DEFAULT,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
fun TaskParameterInput(
    type: TaskType,
    params: Map<String, String>,
    onParamChange: (String, String) -> Unit
) {
    val spacing = TriplexDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        when (type) {
            TaskType.ITEM_RETURN -> {
                Text(
                    "Samsung Support - 1-800-SAMSUNG",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextInput(
                    value = params["product"].orEmpty(),
                    onValueChange = { onParamChange("product", it) },
                    label = "Item or model",
                    placeholder = "Galaxy S26 Ultra",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["order_number"].orEmpty(),
                    onValueChange = { onParamChange("order_number", it) },
                    label = "Order number",
                    placeholder = "US123456789",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["return_reason"].orEmpty(),
                    onValueChange = { onParamChange("return_reason", it) },
                    label = "Why are you returning it?",
                    placeholder = "The screen arrived cracked",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["desired_outcome"].orEmpty(),
                    onValueChange = { onParamChange("desired_outcome", it) },
                    label = "What should success look like?",
                    placeholder = "A full refund to the original payment method",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["approval_policy"].orEmpty(),
                    onValueChange = { onParamChange("approval_policy", it) },
                    label = "Limits Triplex must not cross",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TaskType.APPOINTMENT_MODIFY -> {
                OutlinedTextInput(
                    value = params["appointment_id"].orEmpty(),
                    onValueChange = { onParamChange("appointment_id", it) },
                    label = "Appointment ID",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["new_date"].orEmpty(),
                    onValueChange = { onParamChange("new_date", it) },
                    label = "New date and time",
                    placeholder = "YYYY-MM-DD HH:MM",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["request"].orEmpty(),
                    onValueChange = { onParamChange("request", it) },
                    label = "What should change?",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TaskType.RESERVATION_UPDATE -> {
                OutlinedTextInput(
                    value = params["reservation_id"].orEmpty(),
                    onValueChange = { onParamChange("reservation_id", it) },
                    label = "Reservation ID",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["party_size"].orEmpty(),
                    onValueChange = { onParamChange("party_size", it) },
                    label = "New party size",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextInput(
                    value = params["notes"].orEmpty(),
                    onValueChange = { onParamChange("notes", it) },
                    label = "Additional notes",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
