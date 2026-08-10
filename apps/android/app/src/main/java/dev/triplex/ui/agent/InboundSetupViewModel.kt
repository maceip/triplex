package dev.triplex.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.data.repository.Result
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate
import dev.triplex.domain.model.AutomationVoicePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboundSetupState(
    val config: AgentInboundConfig = AgentInboundConfig(),
    val clonedVoiceReady: Boolean = false,
    /** Why the last write was refused. Cleared on the next successful one. */
    val voiceError: String? = null,
) {
    val automations: List<AutomationTemplate> get() = AutomationCatalog.inbound
}

/**
 * Backs `agent/inbound`.
 *
 * Every write goes through [AgentConfigRepository]; the screen holds no draft
 * state of its own beyond the greeting field, so what is on screen is what the
 * SIP engine will read on the next call.
 */
@HiltViewModel
class InboundSetupViewModel @Inject constructor(
    private val agentConfig: AgentConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(InboundSetupState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            agentConfig.inbound.collect { inbound ->
                _state.value = _state.value.copy(config = inbound)
            }
        }
        refreshVoiceReadiness()
    }

    fun refreshVoiceReadiness() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                clonedVoiceReady = agentConfig.clonedVoiceAvailable(),
            )
        }
    }

    fun setAutoAnswerAll(enabled: Boolean) {
        viewModelScope.launch { agentConfig.setAutoAnswerAll(enabled) }
    }

    fun setGreetingText(text: String) {
        viewModelScope.launch { agentConfig.setGreetingText(text) }
    }

    fun setAutomationEnabled(automationId: String, enabled: Boolean) {
        viewModelScope.launch { agentConfig.setAutomationEnabled(automationId, enabled) }
    }

    /**
     * Refuses rather than downgrades: selecting the cloned voice without a ready
     * profile leaves the stored policy untouched and surfaces the reason
     * (RUNTIME_INVARIANTS.md §7.6).
     */
    fun setVoicePolicy(policy: AutomationVoicePolicy) {
        viewModelScope.launch {
            _state.value = when (val result = agentConfig.setInboundVoicePolicy(policy)) {
                is Result.Success -> _state.value.copy(voiceError = null)
                is Result.Error -> _state.value.copy(voiceError = result.message)
            }
        }
    }

    fun setQuickReplies(replies: List<String>) {
        viewModelScope.launch { agentConfig.setQuickReplies(replies) }
    }
}
