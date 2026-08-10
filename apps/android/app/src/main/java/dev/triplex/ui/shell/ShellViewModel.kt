package dev.triplex.ui.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The shell's only piece of state: is a call on screen right now.
 *
 * [TriplexShell] needs this to hide the bottom bar and pull navigation back to
 * the keypad, and it is the one thing the shell cannot take as a parameter
 * without the caller re-plumbing it. Reading it from the repository here keeps
 * the shell's signature about presentation.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    repository: CallSessionRepository,
) : ViewModel() {
    val session: StateFlow<CallSession?> = repository.session
}
