package dev.triplex.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.local.db.AgentRunDao
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.domain.model.AgentStatus
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.telephony.sip.OutboundCallCoordinator
import dev.triplex.telephony.sip.TelephonyController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentHomeState(
    val tasks: List<TaskDefinition> = emptyList(),
    val activeTask: TaskDefinition? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val autoAnswerAll: Boolean = true,
    val clonedVoiceReady: Boolean = false,
    val routingAttested: Boolean = false,
    val hasSipCredentials: Boolean = false,
)

/**
 * Agent home: the answering state, the run history, and the active task.
 *
 * Keeps the task lifecycle the dashboard already had (load, start through
 * [OutboundCallCoordinator], poll, stop) and adds the two things Phase 3
 * introduces: the readiness header and the local run list.
 */
@HiltViewModel
class AgentHomeViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val outboundCallCoordinator: OutboundCallCoordinator,
    private val agentConfig: AgentConfigRepository,
    private val telephonyController: TelephonyController,
    private val secureStorage: SecureStorage,
    runDao: AgentRunDao,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentHomeState())
    val state = _state.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus.IDLE)
    val agentStatus = _agentStatus.asStateFlow()

    /** SIP registration, the honest answer to "can the agent take a call?". */
    val sipState = telephonyController.sipState

    val runs = runDao.observeRuns(RUN_HISTORY_LIMIT)
        .map { rows -> rows.map { it.toAgentRun() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private var pollingJob: Job? = null

    init {
        loadTasks()
        viewModelScope.launch {
            agentConfig.inbound.collect { inbound ->
                _state.value = _state.value.copy(autoAnswerAll = inbound.autoAnswerAll)
            }
        }
        refreshVoiceReadiness()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = taskRepository.getTasks()) {
                is Result.Success ->
                    _state.value = _state.value.copy(tasks = result.data, loading = false)

                is Result.Error ->
                    _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /** Re-read on resume: the clone can become ready while this screen is open. */
    fun refreshVoiceReadiness() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                clonedVoiceReady = agentConfig.clonedVoiceAvailable(),
                routingAttested = secureStorage.isRoutingAttested(),
                hasSipCredentials = !secureStorage.getPlivoUsername().isNullOrBlank() &&
                    !secureStorage.getPlivoPassword().isNullOrBlank(),
            )
        }
    }

    fun startTask(taskId: String) {
        viewModelScope.launch {
            _agentStatus.value = AgentStatus.PREPARING
            when (val result = outboundCallCoordinator.startTask(taskId)) {
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

    fun stopTask(taskId: String) {
        viewModelScope.launch {
            when (taskRepository.stopTask(taskId)) {
                is Result.Success -> {
                    loadTasks()
                    _state.value = _state.value.copy(activeTask = null)
                    _agentStatus.value = AgentStatus.IDLE
                    stopPolling()
                }

                is Result.Error ->
                    _state.value = _state.value.copy(error = "Failed to stop task")
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun startPolling(taskId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val result = taskRepository.getTask(taskId)) {
                    is Result.Success -> {
                        val task = result.data
                        _state.value = _state.value.copy(activeTask = task)
                        if (task.status in TERMINAL_STATUSES) {
                            _agentStatus.value = AgentStatus.IDLE
                            stopPolling()
                            loadTasks()
                        }
                    }

                    is Result.Error -> _agentStatus.value = AgentStatus.ERROR
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

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val RUN_HISTORY_LIMIT = 50
        val TERMINAL_STATUSES = setOf("completed", "failed", "stopped")
    }
}
