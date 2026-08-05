package dev.triplex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.TaskRepository
import dev.triplex.domain.model.TaskType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateTaskState(
    val selectedType: TaskType? = null,
    val destinationNumber: String = "",
    val params: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val createdTaskId: String? = null
)

@HiltViewModel
class CreateTaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CreateTaskState())
    val state = _state.asStateFlow()

    fun selectTaskType(type: TaskType) {
        _state.value = _state.value.copy(
            selectedType = type,
            params = emptyMap(),
            error = null
        )
    }

    fun updateParam(key: String, value: String) {
        _state.value = _state.value.copy(
            params = _state.value.params + (key to value)
        )
    }

    fun updateDestinationNumber(number: String) {
        _state.value = _state.value.copy(
            destinationNumber = number,
            error = null
        )
    }

    fun createTask() {
        val type = _state.value.selectedType ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            
            when (val result = taskRepository.createTask(
                taskType = type.name,
                destinationNumber = _state.value.destinationNumber,
                taskParams = _state.value.params
            )) {
                is Result.Success -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        createdTaskId = result.data.id
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
}
