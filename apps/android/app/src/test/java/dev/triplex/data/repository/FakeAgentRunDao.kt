package dev.triplex.data.repository

import dev.triplex.data.local.db.AgentCallRunEntity
import dev.triplex.data.local.db.AgentRunDao
import dev.triplex.data.local.db.AgentRunWithTurns
import dev.triplex.data.local.db.AgentTurnEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * In-memory [AgentRunDao].
 *
 * The DAO interface names no Room runtime type, so the recorder's real
 * write-through logic runs on the JVM against this: same upsert-by-primary-key
 * semantics, same targeted `finishRun` UPDATE, no Robolectric and no emulator.
 */
class FakeAgentRunDao : AgentRunDao {

    private val runRows = MutableStateFlow<Map<String, AgentCallRunEntity>>(emptyMap())
    private val turnRows = MutableStateFlow<Map<Pair<String, Int>, AgentTurnEntity>>(emptyMap())

    /** Write counts, so a test can assert an unchanged session costs no write. */
    var runWrites = 0
        private set

    var turnWrites = 0
        private set

    override suspend fun upsertRun(run: AgentCallRunEntity) {
        runWrites++
        runRows.value = runRows.value + (run.id to run)
    }

    override suspend fun upsertTurns(turns: List<AgentTurnEntity>) {
        turnWrites++
        turnRows.value = turnRows.value + turns.associateBy { it.runId to it.seq }
    }

    override fun observeRuns(limit: Int): Flow<List<AgentRunWithTurns>> =
        combine(runRows, turnRows) { runs, turns ->
            runs.values
                .sortedByDescending(AgentCallRunEntity::startedAtMs)
                .take(limit)
                .map { run -> AgentRunWithTurns(run, turns.values.filter { it.runId == run.id }) }
        }

    override fun observeRun(runId: String): Flow<AgentRunWithTurns?> =
        combine(runRows, turnRows) { runs, turns ->
            runs[runId]?.let { run ->
                AgentRunWithTurns(run, turns.values.filter { it.runId == runId })
            }
        }

    override suspend fun findRun(runId: String): AgentCallRunEntity? = runRows.value[runId]

    override suspend fun finishRun(
        runId: String,
        endedAtMs: Long,
        endReason: String,
        outcomeSummary: String?,
    ) {
        // A targeted UPDATE, exactly like the @Query: a row that is not there is
        // not created, and the untouched columns are left alone.
        val existing = runRows.value[runId] ?: return
        runRows.value = runRows.value + (
            runId to existing.copy(
                endedAtMs = endedAtMs,
                endReason = endReason,
                outcomeSummary = outcomeSummary,
            )
            )
    }

    fun run(runId: String): AgentCallRunEntity? = runRows.value[runId]

    fun turnsOf(runId: String): List<AgentTurnEntity> = turnRows.value.values
        .filter { it.runId == runId }
        .sortedBy(AgentTurnEntity::seq)
}
