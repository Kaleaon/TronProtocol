package com.tronprotocol.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tronprotocol.app.data.entity.TelemetryEntity

/**
 * Data Access Object for retrieval telemetry events.
 *
 * Replaces JSONL file writes with structured SQL storage,
 * enabling aggregate analytics queries.
 */
@Dao
interface TelemetryDao {

    @Insert
    fun insert(event: TelemetryEntity)

    @Query("SELECT * FROM retrieval_telemetry WHERE aiId = :aiId ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecent(aiId: String, limit: Int): List<TelemetryEntity>

    @Query("SELECT AVG(latencyMs) FROM retrieval_telemetry WHERE aiId = :aiId AND strategy = :strategy")
    fun getAvgLatency(aiId: String, strategy: String): Float?

    @Query("SELECT AVG(avgScore) FROM retrieval_telemetry WHERE aiId = :aiId AND strategy = :strategy")
    fun getAvgScore(aiId: String, strategy: String): Float?

    @Query("SELECT COUNT(*) FROM retrieval_telemetry WHERE aiId = :aiId")
    fun countByAiId(aiId: String): Int

    @Query("DELETE FROM retrieval_telemetry WHERE timestampMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long)
}
