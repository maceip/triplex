package dev.triplex.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the local run history (reskin.md §2.4).
 *
 * This interface is the seam the recorder and the agent screens depend on. It
 * names no Room runtime type, so a JVM test substitutes an in-memory
 * implementation and exercises the real write-through logic without Robolectric
 * or an emulator.
 *
 * Every write is idempotent by primary key. The recorder writes the same run row
 * repeatedly as the call develops and re-writes a turn whose partial text was
 * revised; nothing here requires read-modify-write, so a crash between any two
 * writes leaves a consistent, partial run rather than a torn one.
 *
 * `@Relation` does not order its child rows, so callers sort turns by
 * [AgentTurnEntity.seq]; [dev.triplex.ui.agent.toAgentRun] does it once.
 */
@Dao
interface AgentRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: AgentCallRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurns(turns: List<AgentTurnEntity>)

    /**
     * The agent-home run list, newest first (reskin.md §3.2 names this).
     *
     * @param limit how many runs to keep on screen. History is local and never
     *   pruned, so the query is what bounds the read.
     */
    @Transaction
    @Query("SELECT * FROM agent_call_runs ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRuns(limit: Int): Flow<List<AgentRunWithTurns>>

    /** Backing flow for `agent/run/{runId}`. */
    @Transaction
    @Query("SELECT * FROM agent_call_runs WHERE id = :runId")
    fun observeRun(runId: String): Flow<AgentRunWithTurns?>

    @Query("SELECT * FROM agent_call_runs WHERE id = :runId")
    suspend fun findRun(runId: String): AgentCallRunEntity?

    /**
     * Closes a run out.
     *
     * A targeted UPDATE rather than a whole-row rewrite, so finishing a run can
     * never resurrect a stale copy of the fields the recorder is not changing.
     */
    @Query(
        """
        UPDATE agent_call_runs
        SET endedAtMs = :endedAtMs, endReason = :endReason, outcomeSummary = :outcomeSummary
        WHERE id = :runId
        """
    )
    suspend fun finishRun(
        runId: String,
        endedAtMs: Long,
        endReason: String,
        outcomeSummary: String?,
    )
}
