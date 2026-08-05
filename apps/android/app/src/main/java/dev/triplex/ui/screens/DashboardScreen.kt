package dev.triplex.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import dev.triplex.domain.model.AgentStatus
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexReveal
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexSignalOrb
import dev.triplex.ui.components.TriplexStatusPill
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.components.TriplexTopBarAction
import dev.triplex.ui.theme.TriplexDesign

@Composable
fun DashboardScreen(
    onCreateTask: () -> Unit,
    onTaskSelected: (String) -> Unit,
    onVoiceClone: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()
    val spacing = TriplexDesign.spacing
    val motion = TriplexDesign.motion

    TriplexBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TriplexTopBar(
                    title = "Triplex",
                    actions = {
                        TriplexTopBarAction(text = "My voice", onClick = onVoiceClone)
                    }
                )
            },
            floatingActionButton = {
                TriplexButton(
                    text = "New task",
                    onClick = onCreateTask,
                    leadingIcon = Icons.Default.Add
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    top = spacing.large,
                    bottom = 112.dp
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.large)
            ) {
                item {
                    TriplexReveal {
                        AgentHero(
                            status = agentStatus
                        )
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = state.activeTask != null,
                        enter = fadeIn(tween(motion.standardMillis)) + expandVertically(),
                        exit = fadeOut(tween(motion.quickMillis)) + shrinkVertically()
                    ) {
                        state.activeTask?.let { task ->
                            ActiveTaskCard(task = task, onStop = { viewModel.stopTask(task.id) })
                        }
                    }
                }

                item {
                    TriplexScreenHeader(
                        eyebrow = "ACTIVITY",
                        title = "Your tasks",
                        description = "Every call stays bounded, visible, and stoppable."
                    )
                }

                state.error?.let { error ->
                    item {
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
                }

                when {
                    state.loading -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.xxLarge),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.tasks.isEmpty() -> item {
                        EmptyTasksCard(onCreateTask = onCreateTask)
                    }
                    else -> items(state.tasks, key = { it.id }) { task ->
                        TaskListItem(
                            task = task,
                            onClick = { onTaskSelected(task.id) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentHero(status: AgentStatus) {
    val spacing = TriplexDesign.spacing
    val statusColor by animateColorAsState(
        targetValue = agentStatusColor(status),
        animationSpec = tween(TriplexDesign.motion.standardMillis),
        label = "Agent status color"
    )
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xLarge),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriplexSignalOrb(
                active = status == AgentStatus.ACTIVE,
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(25.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.small)
            ) {
                TriplexStatusPill(
                    text = statusLabel(status),
                    tone = statusTone(status),
                    leadingColor = statusColor
                )
                Text(statusHeadline(status), style = MaterialTheme.typography.titleLarge)
                Text(
                    statusDetail(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
fun ActiveTaskCard(
    task: TaskDefinition,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = modifier.fillMaxWidth(), tone = TriplexCardTone.SUCCESS) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xSmall)
            ) {
                TriplexStatusPill(
                    text = "ACTIVE TASK",
                    tone = TriplexCardTone.SUCCESS,
                    leadingColor = TriplexDesign.colors.success
                )
                Text(taskTypeLabel(task.task_type), style = MaterialTheme.typography.titleMedium)
                Text(task.destination_number, style = MaterialTheme.typography.bodyMedium)
            }
            TriplexButton(
                text = "Stop",
                onClick = onStop,
                style = TriplexButtonStyle.DANGER
            )
        }
    }
}

@Composable
fun TaskListItem(
    task: TaskDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(taskTypeLabel(task.task_type), style = MaterialTheme.typography.titleMedium)
                TriplexStatusPill(
                    text = task.status.uppercase(),
                    tone = taskStatusTone(task.status)
                )
            }
            Text(task.destination_number, style = MaterialTheme.typography.bodyMedium)
            task.outcome?.let { outcome ->
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyTasksCard(onCreateTask: () -> Unit) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.large)
        ) {
            Text("Nothing running", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create a reservation or appointment task. Triplex will show its live state here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TriplexButton(
                text = "Create your first task",
                onClick = onCreateTask,
                leadingIcon = Icons.Default.Add,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun agentStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.IDLE -> MaterialTheme.colorScheme.outline
    AgentStatus.PREPARING -> TriplexDesign.colors.warning
    AgentStatus.READY -> TriplexDesign.colors.success
    AgentStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
    AgentStatus.ERROR -> MaterialTheme.colorScheme.error
}

private fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> "IDLE"
    AgentStatus.PREPARING -> "STARTING TASK"
    AgentStatus.READY -> "READY"
    AgentStatus.ACTIVE -> "TASK ACTIVE"
    AgentStatus.ERROR -> "NEEDS ATTENTION"
}

private fun statusHeadline(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> "Agent is standing by"
    AgentStatus.PREPARING -> "Authorizing the bounded task"
    AgentStatus.READY -> "Your agent is ready"
    AgentStatus.ACTIVE -> "Triplex is tracking the task"
    AgentStatus.ERROR -> "Triplex needs a quick check"
}

private fun statusDetail(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> "Ready for your next approved task"
    AgentStatus.PREPARING -> "The gateway is validating the task and destination"
    AgentStatus.READY -> "Phone runtime is warm; placement appears when execution is measured"
    AgentStatus.ACTIVE -> "Task state is live; call execution depends on provider availability"
    AgentStatus.ERROR -> "Open the task state for the latest evidenced outcome"
}

private fun statusTone(status: AgentStatus): TriplexCardTone = when (status) {
    AgentStatus.READY, AgentStatus.ACTIVE -> TriplexCardTone.SUCCESS
    AgentStatus.PREPARING -> TriplexCardTone.WARNING
    AgentStatus.ERROR -> TriplexCardTone.DANGER
    AgentStatus.IDLE -> TriplexCardTone.DEFAULT
}

private fun taskStatusTone(status: String): TriplexCardTone = when (status.lowercase()) {
    "completed" -> TriplexCardTone.SUCCESS
    "failed" -> TriplexCardTone.DANGER
    "active" -> TriplexCardTone.ACCENT
    "stopped" -> TriplexCardTone.WARNING
    else -> TriplexCardTone.DEFAULT
}

private fun taskTypeLabel(raw: String): String = raw
    .lowercase()
    .split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
