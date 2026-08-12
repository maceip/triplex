package dev.triplex.debug

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only navigation requests from ADB broadcasts into [dev.triplex.ui.shell.TriplexShell].
 * Release builds do not include the receiver that writes here.
 */
@Singleton
class DebugNavigationBus @Inject constructor() {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    fun open(route: String) {
        _requests.tryEmit(route)
    }

    companion object {
        const val ROUTE_AGENT_VOICE = "agent/voice"
        const val ROUTE_AGENT_HOME = "agent/home"
    }
}
