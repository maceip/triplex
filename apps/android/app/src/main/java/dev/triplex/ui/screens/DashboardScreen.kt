package dev.triplex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.AgentStatus
import dev.triplex.domain.model.TaskDefinition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onCreateTask: () -> Unit,
    onTaskSelected: (String) -> Unit,
    onVoiceClone: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Triplex") },
                actions = {
                    TextButton(onClick = onVoiceClone) { Text("My Voice") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateTask) {
                Icon(Icons.Default.Add, contentDescription = "New Task")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatusBadge(
                status = agentStatus,
                placement = state.activeTask?.let { 
                    dev.triplex.domain.model.Placement.LOCAL 
                },
                modifier = Modifier.padding(16.dp)
            )

            state.activeTask?.let { task ->
                ActiveTaskCard(
                    task = task,
                    onStop = { viewModel.stopTask(task.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Text(
                text = "Tasks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.tasks.isEmpty()) {
                Text(
                    text = "No tasks yet. Create one to begin.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskListItem(
                            task = task,
                            onClick = { onTaskSelected(task.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    status: AgentStatus,
    placement: dev.triplex.domain.model.Placement?,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        AgentStatus.IDLE -> MaterialTheme.colorScheme.outline
        AgentStatus.PREPARING -> MaterialTheme.colorScheme.tertiary
        AgentStatus.READY -> MaterialTheme.colorScheme.primary
        AgentStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
        AgentStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier,
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = status.name,
                color = MaterialTheme.colorScheme.onPrimary
            )
            placement?.let {
                Text(
                    text = "(${it.name})",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall
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
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = task.task_type,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = task.destination_number,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = onStop) {
                Text("Stop")
            }
        }
    }
}

@Composable
fun TaskListItem(
    task: TaskDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.task_type,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = task.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.status) {
                        "completed" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        "active" -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
            Text(
                text = task.destination_number,
                style = MaterialTheme.typography.bodyMedium
            )
            task.outcome?.let { outcome ->
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
