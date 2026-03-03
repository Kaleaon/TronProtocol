package com.tronprotocol.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tronprotocol.app.data.entity.ChunkEntity

/**
 * Data Access Object for RAG chunks.
 *
 * Provides CRUD operations and FTS4 full-text search, replacing
 * in-memory linear scans with indexed SQL queries.
 */
@Dao
interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(chunk: ChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(chunks: List<ChunkEntity>)

    @Update
    fun update(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE chunkId = :chunkId")
    fun getById(chunkId: String): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE aiId = :aiId ORDER BY timestamp DESC")
    fun getByAiId(aiId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE aiId = :aiId ORDER BY qValue ASC LIMIT :limit")
    fun getLowestQValue(aiId: String, limit: Int): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE aiId = :aiId ORDER BY timestamp DESC LIMIT :limit")
    fun getMostRecent(aiId: String, limit: Int): List<ChunkEntity>

    @Query("DELETE FROM chunks WHERE chunkId = :chunkId")
    fun deleteById(chunkId: String)

    @Query("DELETE FROM chunks WHERE aiId = :aiId")
    fun deleteByAiId(aiId: String)

    @Query("SELECT COUNT(*) FROM chunks WHERE aiId = :aiId")
    fun countByAiId(aiId: String): Int

    /**
     * FTS4 full-text search over chunk content.
     * Uses the FTS index for fast keyword matching.
     */
    @Query(
        """
        SELECT chunks.* FROM chunks
        JOIN chunks_fts ON chunks.rowid = chunks_fts.rowid
        WHERE chunks_fts MATCH :query AND chunks.aiId = :aiId
        ORDER BY chunks.qValue DESC
        LIMIT :limit
        """
    )
    fun searchFts(query: String, aiId: String, limit: Int): List<ChunkEntity>

    /**
     * Get aggregate stats for an AI's chunks.
     */
    @Query(
        """
        SELECT AVG(qValue) as avgQ, SUM(retrievalCount) as totalRetrievals,
               SUM(successCount) as totalSuccesses, COUNT(*) as totalChunks
        FROM chunks WHERE aiId = :aiId
        """
    )
    fun getStats(aiId: String): ChunkStats?
}

data class ChunkStats(
    val avgQ: Float?,
    val totalRetrievals: Int?,
    val totalSuccesses: Int?,
    val totalChunks: Int?
)
