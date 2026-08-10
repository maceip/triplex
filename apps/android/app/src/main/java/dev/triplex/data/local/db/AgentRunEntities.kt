package dev.triplex.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * The persisted shape of an agent call run (reskin.md §2.4).
 *
 * **Local only.** Screened-call transcripts are sensitive, so nothing in this
 * schema is synced to the gateway (§5, decision 4). There is deliberately no
 * upload path and no remote id column.
 *
 * Enum-valued columns are stored as their `name` rather than through a
 * `TypeConverter`: the strings are what a `sqlite3` dump has to be readable in
 * during a device investigation, and it keeps the entity constructible from a
 * plain JVM test with no Room runtime on the classpath.
 */
@Entity(tableName = "agent_call_runs")
data class AgentCallRunEntity(
    @PrimaryKey val id: String,
    /** `CallStack.name` — TELECOM or SIP. */
    val stack: String,
    /** `CallDirection.name` — INCOMING or OUTGOING. */
    val direction: String,
    /** `AgentMode.name`. Never NONE: a run only exists once the agent attached. */
    val agentMode: String,
    val counterpartNumber: String,
    val counterpartName: String,
    val automationId: String? = null,
    val taskId: String? = null,
    val startedAtMs: Long,
    /** Null while the call is still up, or if the process died mid-call. */
    val endedAtMs: Long? = null,
    /** [AgentRunEndReason] name, or null for a run that never got to finish. */
    val endReason: String? = null,
    val outcomeSummary: String? = null,
)

/**
 * One transcript turn.
 *
 * Keyed on `(runId, seq)` and written with REPLACE semantics so a streaming
 * partial can be revised in place as the recognizer firms it up, without the
 * recorder having to read back what it already stored.
 */
@Entity(
    tableName = "agent_turns",
    primaryKeys = ["runId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = AgentCallRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class AgentTurnEntity(
    val runId: String,
    /** Position in the call's timeline. Stable for the life of the run. */
    val seq: Int,
    /** [AgentTurnSpeaker] name. */
    val speaker: String,
    val text: String,
    val atMs: Long,
    /** False while the recognizer may still revise [text]. */
    val final: Boolean,
)

enum class AgentTurnSpeaker { AGENT, CALLER, USER, SYSTEM }

/**
 * Why a run stopped.
 *
 * [UNKNOWN] is the honest value for a run whose session vanished without a
 * lifecycle event — including a run interrupted by process death, which is
 * left with a null [AgentCallRunEntity.endReason] instead.
 */
enum class AgentRunEndReason { COMPLETED, MISSED, UNKNOWN }

/** A run and its turns, which is how both the list and the detail read it. */
data class AgentRunWithTurns(
    @Embedded val run: AgentCallRunEntity,
    @Relation(parentColumn = "id", entityColumn = "runId")
    val turns: List<AgentTurnEntity>,
)
