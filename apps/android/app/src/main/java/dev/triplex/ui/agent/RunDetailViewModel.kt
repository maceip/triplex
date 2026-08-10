package dev.triplex.ui.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.local.db.AgentRunDao
import dev.triplex.ui.shell.ShellRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs `agent/run/{runId}`.
 *
 * Reads the run id from the type-safe route rather than from a string argument,
 * and observes the row so a transcript that is still being written updates while
 * the screen is open.
 */
@HiltViewModel
class RunDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    runDao: AgentRunDao,
) : ViewModel() {

    private val runId: String = savedStateHandle.toRoute<ShellRoute.AgentRunDetail>().runId

    val run = runDao.observeRun(runId)
        .map { it?.toAgentRun() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
