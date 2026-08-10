package dev.triplex.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The device's only database: agent call runs and their transcripts.
 *
 * `exportSchema = false` because there is no migration test suite to feed and
 * no schema to publish — the store is private to the device (reskin.md §5).
 */
@Database(
    entities = [AgentCallRunEntity::class, AgentTurnEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TriplexDatabase : RoomDatabase() {
    abstract fun agentRunDao(): AgentRunDao

    companion object {
        const val NAME = "triplex-runs.db"
    }
}
