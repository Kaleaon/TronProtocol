package com.tronprotocol.app.plugins

import android.util.Log
import com.tronprotocol.app.rag.RAGStore

/**
 * Bridge between the Brief plugin and TronProtocol's RAG memory system.
 *
 * Converts Brief extraction/summarization results into RAGStore TextChunks,
 * enabling briefed content to be retrieved via semantic search, MemRL Q-value
 * ranking, and knowledge graph traversal.
 */
class BriefRAGBridge(private val ragStore: RAGStore) {

    companion object {
        private const val TAG = "BriefRAGBridge"
        private const val SOURCE_TYPE = "brief"
    }

    /**
     * Result from a Brief operation, ready for RAG ingestion.
     */
    data class BriefResult(
        val url: String,
        val query: String,
        val depth: Int,
        val summary: String
    )

    /**
     * Ingest a single Brief result into the RAG store.
     *
     * @param url The URL that was briefed
     * @param query The query used for summarization
     * @param depth The depth level (0-2) used
     * @param summary The generated summary text
     * @return The chunk ID assigned by RAGStore
     */
    fun ingestBriefResult(
        url: String,
        query: String,
        depth: Int,
        summary: String
    ): String {
        if (summary.isBlank()) {
            Log.w(TAG, "Skipping RAG ingest: empty summary for $url")
            return ""
        }

        val metadata = mapOf<String, Any>(
            "brief_url" to url,
            "brief_query" to query,
            "brief_depth" to depth,
            "ingested_at" to System.currentTimeMillis(),
            "importance" to depthToImportance(depth)
        )

        return try {
            val chunkId = ragStore.addChunk(
                content = summary,
                source = url,
                sourceType = SOURCE_TYPE,
                metadata = metadata
            )
            Log.d(TAG, "Ingested brief into RAG: chunk=$chunkId, url=$url, depth=$depth")
            chunkId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ingest brief into RAG: ${e.message}")
            ""
        }
    }

    /**
     * Ingest multiple Brief results into the RAG store.
     *
     * @param results List of Brief results to ingest
     * @return List of chunk IDs (empty string for failed ingestions)
     */
    fun ingestBatchResults(results: List<BriefResult>): List<String> {
        return results.map { result ->
            ingestBriefResult(result.url, result.query, result.depth, result.summary)
        }
    }

    /**
     * Map Brief depth levels to RAG importance scores.
     * Higher depth = more detailed content = higher importance.
     */
    private fun depthToImportance(depth: Int): Float = when (depth) {
        0 -> 0.4f  // Headlines are low-importance triage
        1 -> 0.6f  // Standard summaries are moderately important
        2 -> 0.8f  // Detailed analyses are high-importance
        else -> 0.5f
    }
}
