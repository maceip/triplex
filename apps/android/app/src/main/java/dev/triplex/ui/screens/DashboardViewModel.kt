package dev.triplex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.data.repository.UserRepository
import dev.triplex.domain.model.AgentStatus
import dev.triplex.domain.model.TaskState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TaskState())
    val state = _state.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus.IDLE)
    val agentStatus = _agentStatus.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            
            when (val result = taskRepository.getTasks()) {
                is Result.Success -> {
                    _state.value = _state.value.copy(
                        tasks = result.data,
                        loading = false
                    )
                }
                is Result.Error -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun stopTask(taskId: String) {
        viewModelScope.launch {
            when (taskRepository.stopTask(taskId)) {
                is Result.Success -> {
                    loadTasks()
                    _agentStatus.value = AgentStatus.IDLE
                    stopPolling()
                }
                is Result.Error -> {
                    _state.value = _state.value.copy(error = "Failed to stop task")
                }
            }
        }
    }

    fun startTask(taskId: String) {
        viewModelScope.launch {
            _agentStatus.value = AgentStatus.PREPARING
            
            when (val result = taskRepository.startTask(taskId)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(activeTask = result.data)
                    _agentStatus.value = AgentStatus.ACTIVE
                    startPolling(taskId)
                }
                is Result.Error -> {
                    _agentStatus.value = AgentStatus.ERROR
                    _state.value = _state.value.copy(error = result.message)
                }
            }
        }
    }

    private fun startPolling(taskId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                when (val result = taskRepository.getTask(taskId)) {
                    is Result.Success -> {
                        val task = result.data
                        _state.value = _state.value.copy(activeTask = task)
                        if (task.status in listOf("completed", "failed", "stopped")) {
                            _agentStatus.value = AgentStatus.IDLE
                            stopPolling()
                            loadTasks()
                        }
                    }
                    is Result.Error -> {
                        _agentStatus.value = AgentStatus.ERROR
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
