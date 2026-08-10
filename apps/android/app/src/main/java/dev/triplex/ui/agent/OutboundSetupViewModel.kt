package dev.triplex.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentOutboundConfig
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.domain.model.TaskDefinition
import dev.triplex.domain.model.TaskType
import dev.triplex.telephony.sip.OutboundCallCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OutboundSetupState(
    val tasks: List<TaskDefinition> = emptyList(),
    val config: AgentOutboundConfig = AgentOutboundConfig(),
    val clonedVoiceReady: Boolean = false,
    val selectedType: TaskType? = null,
    val destinationNumber: String = "",
    val params: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val dialing: Boolean = false,
    val error: String? = null,
    /** Set while "Call now" is waiting for the user to confirm. */
    val pendingDialTaskId: String? = null,
)

/**
 * Backs `agent/outbound`: the tasks the agent places on your behalf.
 *
 * Folds in the old create-task flow — same fields, same parameters, same
 * gateway calls — and adds the outbound defaults plus the call-time voice check.
 */
@HiltViewModel
class OutboundSetupViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val outboundCallCoordinator: OutboundCallCoordinator,
    private val agentConfig: AgentConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OutboundSetupState())
    val state = _state.asStateFlow()

    init {
        loadTasks()
        viewModelScope.launch {
            agentConfig.outbound.collect { outbound ->
                _state.value = _state.value.copy(config = outbound)
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

    fun refreshVoiceReadiness() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                clonedVoiceReady = agentConfig.clonedVoiceAvailable(),
            )
        }
    }

    fun selectTaskType(type: TaskType) {
        _state.value = _state.value.copy(
            selectedType = type,
            destinationNumber = if (type == TaskType.ITEM_RETURN) SAMSUNG_SUPPORT_NUMBER else "",
            params = if (type == TaskType.ITEM_RETURN) ITEM_RETURN_DEFAULTS else emptyMap(),
            error = null,
        )
    }

    fun updateParam(key: String, value: String) {
        _state.value = _state.value.copy(params = _state.value.params + (key to value))
    }

    fun updateDestinationNumber(number: String) {
        _state.value = _state.value.copy(destinationNumber = number, error = null)
    }

    fun createTask() {
        val type = _state.value.selectedType ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = taskRepository.createTask(
                taskType = type.name,
                destinationNumber = _state.value.destinationNumber,
                taskParams = _state.value.params,
            )
            when (result) {
                is Result.Success -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        selectedType = null,
                        destinationNumber = "",
                        params = emptyMap(),
                    )
                    loadTasks()
                }

                is Result.Error ->
                    _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    fun setDefaultVoicePolicy(policy: AutomationVoicePolicy) {
        viewModelScope.launch {
            _state.value = when (val result = agentConfig.setOutboundDefaultVoicePolicy(policy)) {
                is Result.Success -> _state.value.copy(error = null)
                is Result.Error -> _state.value.copy(error = result.message)
            }
        }
    }

    fun setConfirmBeforeDial(enabled: Boolean) {
        viewModelScope.launch { agentConfig.setConfirmBeforeDial(enabled) }
    }

    /** Asks first when the user wants to be asked; otherwise dials. */
    fun requestDial(taskId: String) {
        if (_state.value.config.confirmBeforeDial) {
            _state.value = _state.value.copy(pendingDialTaskId = taskId, error = null)
        } else {
            dial(taskId)
        }
    }

    fun cancelDial() {
        _state.value = _state.value.copy(pendingDialTaskId = null)
    }

    fun confirmDial() {
        val taskId = _state.value.pendingDialTaskId ?: return
        _state.value = _state.value.copy(pendingDialTaskId = null)
        dial(taskId)
    }

    /**
     * Places the call — or refuses to.
     *
     * The voice policy is resolved *before* the INVITE. If the configured voice
     * is the user's clone and the clone is not ready, the call does not happen:
     * substituting the branded voice would put words in the user's mouth in a
     * voice they did not consent to (RUNTIME_INVARIANTS.md §7.6).
     */
    private fun dial(taskId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(dialing = true, error = null)
            when (val policy = agentConfig.resolveOutboundVoicePolicy()) {
                is Result.Error -> {
                    _state.value = _state.value.copy(dialing = false, error = policy.message)
                    return@launch
                }

                is Result.Success -> Unit
            }
            when (val result = outboundCallCoordinator.startTask(taskId)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(dialing = false)
                    loadTasks()
                }

                is Result.Error ->
                    _state.value = _state.value.copy(dialing = false, error = result.message)
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val SAMSUNG_SUPPORT_NUMBER = "+18007267864"

        val ITEM_RETURN_DEFAULTS = mapOf(
            "company" to "Samsung",
            "approval_policy" to
                "Do not accept fees, store credit, or a replacement without my approval",
        )
    }
}
