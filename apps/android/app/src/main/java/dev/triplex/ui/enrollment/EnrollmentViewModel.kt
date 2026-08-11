package dev.triplex.ui.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.repository.Result
import dev.triplex.data.repository.UserRepository
import dev.triplex.domain.model.EnrollmentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EnrollmentState())
    val state = _state.asStateFlow()

    init {
        checkExistingRegistration()
    }

    private fun checkExistingRegistration() {
        viewModelScope.launch {
            if (userRepository.isRegistered()) {
                val ready = userRepository.getDeviceReady()
                _state.value = _state.value.copy(
                    deviceReady = ready,
                    loading = !ready
                )
                if (!ready) {
                    trySetReady()
                }
            }
        }
    }

    private suspend fun trySetReady() {
        // Enrollment has no SIP endpoint yet, let alone a media path;
        // ScreeningCoordinator makes the media claim once one exists.
        val result = userRepository.setDeviceReady(ready = true, mediaReady = false)
        when (result) {
            is Result.Success -> {
                _state.value = _state.value.copy(
                    deviceReady = true,
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

    fun updateEmail(email: String) {
        _state.value = _state.value.copy(email = email)
    }

    fun updatePhoneNumber(phone: String) {
        _state.value = _state.value.copy(phoneNumber = phone)
    }

    fun registerAndSetup() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)

            when (val result = userRepository.register(_state.value.email, _state.value.phoneNumber)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(user = result.data)
                    userRepository.syncSipCredentials()
                    trySetReady()
                }
                is Result.Error -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = result.message
                    )
                }
            }
            _state.value = _state.value.copy(loading = false)
        }
    }
}
