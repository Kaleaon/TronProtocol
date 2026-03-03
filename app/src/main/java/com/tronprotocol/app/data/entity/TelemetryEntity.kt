package com.tronprotocol.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for retrieval telemetry events.
 *
 * Replaces JSONL file-based telemetry with structured storage,
 * enabling SQL aggregation queries for analytics.
 */
@Entity(tableName = "retrieval_telemetry")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    val aiId: String,
    val strategy: String,
    val latencyMs: Long,
    val resultCount: Int,
    val topK: Int,
    val topScore: Float,
    val avgScore: Float
)
