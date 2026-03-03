package com.tronprotocol.app.data.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Room entity for RAG TextChunk persistence.
 *
 * Provides structured storage with SQL query support, replacing
 * the JSON-in-SecureStorage approach for chunk persistence.
 */
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey
    val chunkId: String,
    val aiId: String,
    val content: String,
    val source: String,
    val sourceType: String,
    val timestamp: Long,
    val tokenCount: Int,
    val qValue: Float = 0.5f,
    val retrievalCount: Int = 0,
    val successCount: Int = 0,
    val metadataJson: String = "{}",
    val embeddingBlob: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkEntity) return false
        return chunkId == other.chunkId
    }

    override fun hashCode(): Int = chunkId.hashCode()
}

/**
 * FTS4 virtual table for full-text search over chunk content.
 * Room generates the FTS index automatically.
 */
@Fts4(contentEntity = ChunkEntity::class)
@Entity(tableName = "chunks_fts")
data class ChunkFts(
    val content: String
)
