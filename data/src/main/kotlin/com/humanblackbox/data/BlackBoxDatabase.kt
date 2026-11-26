package com.humanblackbox.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Queries over metadata-only tables; no raw payload columns exist in this database. */
@Dao
interface BlackBoxDao {
    @Query("SELECT * FROM event_history ORDER BY timestampMs DESC")
    fun observeEvents(): Flow<List<EventHistoryEntity>>

    @Query("SELECT * FROM event_history WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    fun observeEventsSince(sinceMs: Long): Flow<List<EventHistoryEntity>>

    @Query("SELECT eventId FROM event_history")
    suspend fun allEventIds(): List<String>

    @Query("SELECT eventId FROM event_history WHERE timestampMs < :cutoffMs")
    suspend fun eventIdsBefore(cutoffMs: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: EventHistoryEntity)

    @Query("DELETE FROM event_history WHERE eventId = :eventId")
    suspend fun deleteEvent(eventId: String)

    @Query("DELETE FROM event_history WHERE timestampMs < :cutoffMs")
    suspend fun deleteEventsBefore(cutoffMs: Long)

    @Query("DELETE FROM event_history")
    suspend fun deleteAllEvents()

    @Insert
    suspend fun insertDecisionLog(log: DecisionLogEntity)

    @Query("SELECT * FROM decision_log ORDER BY timestampMs DESC")
    fun observeDecisionLogs(): Flow<List<DecisionLogEntity>>

    @Query("DELETE FROM decision_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteDecisionLogsBefore(cutoffMs: Long)

    @Query("DELETE FROM decision_log")
    suspend fun deleteAllDecisionLogs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModelStatus(status: ModelStatusEntity)

    @Query("SELECT * FROM model_status ORDER BY modality")
    fun observeModelStatuses(): Flow<List<ModelStatusEntity>>

    @Query("DELETE FROM model_status")
    suspend fun deleteAllModelStatuses()
}

/**
 * Local Room database for metadata only. Evidence payloads stay in [SecureEvidenceStore] as
 * encrypted files and are never copied into SQLite. The database has no upload component.
 */
@Database(
    entities = [EventHistoryEntity::class, DecisionLogEntity::class, ModelStatusEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BlackBoxDatabase : RoomDatabase() {
    abstract fun blackBoxDao(): BlackBoxDao

    companion object {
        @Volatile
        private var instance: BlackBoxDatabase? = null

        fun get(context: Context): BlackBoxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlackBoxDatabase::class.java,
                    "human-blackbox-metadata.db",
                ).build().also { instance = it }
            }
    }
}
