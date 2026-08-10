package dev.triplex.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.domain.model.TaskType
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexChoiceChip
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexToggleRow
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexDesign
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.alertdialog.AlertDialog
import zed.rainxch.rikkaui.components.ui.alertdialog.AlertDialogAction
import zed.rainxch.rikkaui.components.ui.alertdialog.AlertDialogCancel
import zed.rainxch.rikkaui.components.ui.alertdialog.AlertDialogFooter
import zed.rainxch.rikkaui.components.ui.alertdialog.AlertDialogHeader
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold

/**
 * `agent/outbound` — the calls the agent places for you (reskin.md §3.2).
 *
 * Replaces the standalone create-task screen: the task list, the task form and
 * the outbound defaults are one surface, because they are one decision.
 */
@Composable
fun OutboundSetupScreen(
    onBack: () -> Unit,
    onOpenVoice: () -> Unit,
    viewModel: OutboundSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = TriplexDesign.spacing

    LaunchedEffect(Unit) { viewModel.refreshVoiceReadiness() }

    // Always composed: the dialog owns its own visibility and exit animation, so
    // it is the `open` flag that comes and goes, not the call.
    AlertDialog(
        open = state.pendingDialTaskId != null,
        onDismiss = viewModel::cancelDial,
        // The dialog never dispatches this itself — the action button below is
        // what confirms — and `confirmDial` clears the pending id before it
        // dials, so even a double dispatch cannot place the call twice.
        onConfirm = viewModel::confirmDial,
        label = "Place this call?",
    ) {
        AlertDialogHeader(
            title = "Place this call?",
            description = "Triplex will dial the destination and speak on your behalf.",
        )
        AlertDialogFooter {
            AlertDialogCancel(onClick = viewModel::cancelDial, text = "Cancel")
            AlertDialogAction(text = "Call now", onClick = viewModel::confirmDial)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = "Outgoing calls",
                navigationIcon = RikkaIcons.ArrowLeft,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.large,
                bottom = spacing.xxLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            item { DefaultsCard(state = state, viewModel = viewModel, onOpenVoice = onOpenVoice) }

            state.error?.let { error ->
                item {
                    TriplexCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = TriplexCardTone.DANGER,
                        onClick = viewModel::dismissError,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                TriplexScreenHeader(
                    eyebrow = "TASKS",
                    title = "Ready to call",
                    description = "Each task is bounded: one destination, one goal, " +
                        "and limits the agent must not cross.",
                )
            }

            if (state.tasks.isEmpty()) {
                item {
                    TriplexCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No tasks yet. Create one below.",
                            modifier = Modifier.padding(spacing.large),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        dialing = state.dialing,
                        onCall = { viewModel.requestDial(task.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { NewTaskCard(state = state, viewModel = viewModel) }
        }
    }
}

@Composable
private fun DefaultsCard(
    state: OutboundSetupState,
    viewModel: OutboundSetupViewModel,
    onOpenVoice: () -> Unit,
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            TriplexScreenHeader(
                title = "Defaults",
                description = "Applied to every call the agent places.",
            )
            TriplexToggleRow(
                title = "Ask before dialing",
                checked = state.config.confirmBeforeDial,
                onCheckedChange = viewModel::setConfirmBeforeDial,
                supportingText = "Off: starting a task places the call immediately.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TriplexChoiceChip(
                    label = "Triplex voice",
                    selected = state.config.defaultVoicePolicy == AutomationVoicePolicy.PRESET,
                    onClick = { viewModel.setDefaultVoicePolicy(AutomationVoicePolicy.PRESET) },
                )
                TriplexChoiceChip(
                    label = "My cloned voice",
                    selected = state.config.defaultVoicePolicy == AutomationVoicePolicy.CLONED,
                    onClick = { viewModel.setDefaultVoicePolicy(AutomationVoicePolicy.CLONED) },
                    enabled = state.clonedVoiceReady,
                )
            }
            if (!state.clonedVoiceReady) {
                Text(
                    text = "Your cloned voice is not ready. Calls configured to use it " +
                        "will not be placed until it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TriplexButton(
                    text = "Set up my voice",
                    onClick = onOpenVoice,
                    style = TriplexButtonStyle.SECONDARY,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskDefinition,
    dialing: Boolean,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(taskTypeLabel(task.task_type), style = MaterialTheme.typography.titleMedium)
                TriplexStatusPill(
                    text = task.status.uppercase(),
                    tone = taskStatusTone(task.status),
                )
            }
            Text(task.destination_number, style = MaterialTheme.typography.bodyMedium)
            task.outcome?.let { outcome ->
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TriplexButton(
                text = "Call now",
                onClick = onCall,
                loading = dialing,
                enabled = task.status.lowercase() in CALLABLE_STATUSES,
            )
        }
    }
}

@Composable
private fun NewTaskCard(state: OutboundSetupState, viewModel: OutboundSetupViewModel) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            TriplexScreenHeader(
                title = "New task",
                description = "Pick what the agent should accomplish.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TaskType.entries.forEach { type ->
                    TriplexChoiceChip(
                        label = taskTypeLabel(type.name),
                        selected = state.selectedType == type,
                        onClick = { viewModel.selectTaskType(type) },
                    )
                }
            }
            state.selectedType?.let { type ->
                TaskParameterInput(
                    type = type,
                    params = state.params,
                    onParamChange = viewModel::updateParam,
                )
                OutlinedTextInput(
                    label = "Destination number",
                    value = state.destinationNumber,
                    onValueChange = viewModel::updateDestinationNumber,
                    placeholder = "+1 415 555 0100",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                TriplexButton(
                    text = "Create task",
                    onClick = viewModel::createTask,
                    enabled = state.destinationNumber.isNotBlank(),
                    loading = state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The bounded parameters for each task type.
 *
 * Moved here verbatim from the create-task screen it replaces: these strings are
 * what the gateway's task validation expects, so they are not reworded.
 */
@Composable
private fun TaskParameterInput(
    type: TaskType,
    params: Map<String, String>,
    onParamChange: (String, String) -> Unit,
) {
    val spacing = TriplexDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        when (type) {
            TaskType.ITEM_RETURN -> {
                Text(
                    text = "Samsung Support - 1-800-SAMSUNG",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextInput(
                    label = "Item or model",
                    value = params["product"].orEmpty(),
                    onValueChange = { onParamChange("product", it) },
                    placeholder = "Galaxy S26 Ultra",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "Order number",
                    value = params["order_number"].orEmpty(),
                    onValueChange = { onParamChange("order_number", it) },
                    placeholder = "US123456789",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "Why are you returning it?",
                    value = params["return_reason"].orEmpty(),
                    onValueChange = { onParamChange("return_reason", it) },
                    placeholder = "The screen arrived cracked",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "What should success look like?",
                    value = params["desired_outcome"].orEmpty(),
                    onValueChange = { onParamChange("desired_outcome", it) },
                    placeholder = "A full refund to the original payment method",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "Limits Triplex must not cross",
                    value = params["approval_policy"].orEmpty(),
                    onValueChange = { onParamChange("approval_policy", it) },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TaskType.APPOINTMENT_MODIFY -> {
                OutlinedTextInput(
                    label = "Appointment ID",
                    value = params["appointment_id"].orEmpty(),
                    onValueChange = { onParamChange("appointment_id", it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "New date and time",
                    value = params["new_date"].orEmpty(),
                    onValueChange = { onParamChange("new_date", it) },
                    placeholder = "YYYY-MM-DD HH:MM",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "What should change?",
                    value = params["request"].orEmpty(),
                    onValueChange = { onParamChange("request", it) },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TaskType.RESERVATION_UPDATE -> {
                OutlinedTextInput(
                    label = "Reservation ID",
                    value = params["reservation_id"].orEmpty(),
                    onValueChange = { onParamChange("reservation_id", it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "New party size",
                    value = params["party_size"].orEmpty(),
                    onValueChange = { onParamChange("party_size", it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextInput(
                    label = "Additional notes",
                    value = params["notes"].orEmpty(),
                    onValueChange = { onParamChange("notes", it) },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val CALLABLE_STATUSES = setOf("pending", "active")

private fun taskStatusTone(status: String): TriplexCardTone = when (status.lowercase()) {
    "completed" -> TriplexCardTone.SUCCESS
    "failed" -> TriplexCardTone.DANGER
    "active" -> TriplexCardTone.ACCENT
    "stopped" -> TriplexCardTone.WARNING
    else -> TriplexCardTone.DEFAULT
}
