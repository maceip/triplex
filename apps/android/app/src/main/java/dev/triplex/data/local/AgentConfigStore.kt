package dev.triplex.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persistence primitive under [AgentConfigRepository].
 *
 * Deliberately dumb — a string map with an atomic edit — so the interesting
 * part (defaults, encoding, the cloned-voice gate) lives in the repository and
 * runs on the JVM against an in-memory implementation. DataStore's own
 * durability is Google's code and is not what these tests are for.
 */
interface AgentConfigStore {
    val values: Flow<Map<String, String>>

    suspend fun edit(transform: (Map<String, String>) -> Map<String, String>)
}

private val Context.agentConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "agent_config",
)

@Singleton
class DataStoreAgentConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentConfigStore {

    private val dataStore: DataStore<Preferences> get() = context.agentConfigDataStore

    override val values: Flow<Map<String, String>> = dataStore.data.map { preferences ->
        preferences.asMap()
            .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
            .toMap()
    }

    override suspend fun edit(transform: (Map<String, String>) -> Map<String, String>) {
        dataStore.edit { preferences ->
            val current = preferences.asMap()
                .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
                .toMap()
            val next = transform(current)
            // Removals matter: clearing a key is how a setting returns to its
            // seeded default, which is not the same as writing an empty string.
            current.keys.filterNot(next::containsKey).forEach {
                preferences.remove(stringPreferencesKey(it))
            }
            next.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value }
        }
    }
}
