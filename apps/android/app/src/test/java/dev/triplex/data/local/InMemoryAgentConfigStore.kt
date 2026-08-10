package dev.triplex.data.local

import dev.triplex.voice.VoiceProfileReadiness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [AgentConfigStore] without DataStore.
 *
 * The store is deliberately a dumb string map, so swapping the persistence for
 * this one leaves the interesting behavior — defaults, list encoding, the
 * cloned-voice gate — running as the real repository implements it.
 */
class InMemoryAgentConfigStore(
    initial: Map<String, String> = emptyMap(),
) : AgentConfigStore {

    private val state = MutableStateFlow(initial)

    override val values: Flow<Map<String, String>> = state

    override suspend fun edit(transform: (Map<String, String>) -> Map<String, String>) {
        state.value = transform(state.value)
    }

    /** What is actually persisted, for asserting that a refused write stored nothing. */
    fun snapshot(): Map<String, String> = state.value
}

/** A gateway that reports whatever the test needs, flippable mid-test. */
class FakeVoiceProfileReadiness(var ready: Boolean = false) : VoiceProfileReadiness {
    override suspend fun synthesisReady(): Boolean = ready
}
