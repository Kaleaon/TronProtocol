package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RAG (Retrieval-Augmented Generation) Store
 *
 * Combines features from:
 * - landseek's MemRL self-evolving memory (arXiv:2601.03192)
 * - ToolNeuron's RAG document intelligence
 *
 * Provides:
 * - Per-AI isolated knowledge bases
 * - Multiple retrieval strategies
 * - Q-value learning for memory evolution
 * - Persistent storage with encryption
 */
class RAGStore @Throws(Exception::class) constructor(
    private val context: Context,
    private val aiId: String
) {
    private val storage: SecureStorage = SecureStorage(context)
    private val chunks: MutableList<TextChunk> = mutableListOf()
    private val chunkIndex: MutableMap<String, TextChunk> = mutableMapOf()

    init {
        loadChunks()
    }

    /**
     * Add a memory to the RAG store
     * @param content Memory content
     * @param importance Importance score (0.0 to 1.0)
     */
    @Throws(Exception::class)
    fun addMemory(content: String, importance: Float): String =
        addChunk(content, "memory", "memory", mapOf("importance" to importance))

    /**
     * Add knowledge to the RAG store
     * @param content Knowledge content
     * @param category Category of knowledge
     */
    @Throws(Exception::class)
    fun addKnowledge(content: String, category: String): String =
        addChunk(content, category, "knowledge", mapOf("category" to category))

    /**
     * Add a chunk to the RAG store
     */
    @Throws(Exception::class)
    fun addChunk(
        content: String,
        source: String,
        sourceType: String,
        metadata: Map<String, Any>?
    ): String {
        val chunkId = generateChunkId(content, source)
        val timestamp = System.currentTimeMillis().toString()
        val tokenCount = estimateTokens(content)

        val chunk = TextChunk(chunkId, content, source, sourceType, timestamp, tokenCount)
        if (metadata != null) {
            chunk.metadata = metadata.toMutableMap()
        }

        // Generate embedding (simplified TF-IDF based)
        chunk.embedding = generateEmbedding(content)

        chunks.add(chunk)
        chunkIndex[chunkId] = chunk

        saveChunks()
        Log.d(TAG, "Added chunk: $chunkId for AI: $aiId")

        return chunkId
    }

    /**
     * Retrieve relevant chunks using specified strategy
     */
    fun retrieve(query: String, strategy: RetrievalStrategy, topK: Int): List<RetrievalResult> =
        when (strategy) {
            RetrievalStrategy.SEMANTIC -> retrieveSemantic(query, topK)
            RetrievalStrategy.KEYWORD -> retrieveKeyword(query, topK)
            RetrievalStrategy.HYBRID -> retrieveHybrid(query, topK)
            RetrievalStrategy.RECENCY -> retrieveRecency(query, topK)
            RetrievalStrategy.MEMRL -> retrieveMemRL(query, topK)
            else -> retrieveSemantic(query, topK)
        }

    /**
     * MemRL: Two-phase retrieval with Q-value ranking (arXiv:2601.03192)
     *
     * Phase 1: Semantic retrieval to get candidates
     * Phase 2: Re-rank by learned Q-values (utility)
     */
    private fun retrieveMemRL(query: String, topK: Int): List<RetrievalResult> {
        // Phase 1: Semantic retrieval (get more candidates)
        val semanticResults = retrieveSemantic(query, topK * 3)

        // Phase 2: Re-rank by Q-values
        val reranked = semanticResults.map { result ->
            // Combine semantic score with Q-value
            val semanticScore = result.score
            val qValue = result.chunk.qValue

            // Weighted combination: 70% semantic, 30% learned Q-value
            val combinedScore = 0.7f * semanticScore + 0.3f * qValue

            RetrievalResult(result.chunk, combinedScore, RetrievalStrategy.MEMRL)
        }

        // Sort by combined score and return top-K
        return reranked
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Semantic retrieval using embeddings
     */
    private fun retrieveSemantic(query: String, topK: Int): List<RetrievalResult> {
        val queryEmbedding = generateEmbedding(query)

        val results = chunks
            .filter { it.embedding != null }
            .map { chunk ->
                val similarity = cosineSimilarity(queryEmbedding, chunk.embedding!!)
                RetrievalResult(chunk, similarity, RetrievalStrategy.SEMANTIC)
            }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Keyword-based retrieval
     */
    private fun retrieveKeyword(query: String, topK: Int): List<RetrievalResult> {
        val queryTokens = query.lowercase().split("\\s+".toRegex())

        val results = chunks.mapNotNull { chunk ->
            val content = chunk.content.lowercase()
            val matches = queryTokens.count { token -> content.contains(token) }

            if (matches > 0) {
                val score = matches.toFloat() / queryTokens.size
                RetrievalResult(chunk, score, RetrievalStrategy.KEYWORD)
            } else {
                null
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Hybrid retrieval (combines semantic and keyword)
     */
    private fun retrieveHybrid(query: String, topK: Int): List<RetrievalResult> {
        val semanticResults = retrieveSemantic(query, topK * 2)
        val keywordResults = retrieveKeyword(query, topK * 2)

        val combinedScores = mutableMapOf<String, Float>()

        for (result in semanticResults) {
            combinedScores[result.chunk.chunkId] = result.score * 0.7f
        }

        for (result in keywordResults) {
            val id = result.chunk.chunkId
            val current = combinedScores.getOrDefault(id, 0.0f)
            combinedScores[id] = current + result.score * 0.3f
        }

        val results = combinedScores.mapNotNull { (id, score) ->
            chunkIndex[id]?.let { chunk ->
                RetrievalResult(chunk, score, RetrievalStrategy.HYBRID)
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Recency-based retrieval
     */
    private fun retrieveRecency(query: String, topK: Int): List<RetrievalResult> {
        val results = chunks.mapNotNull { chunk ->
            try {
                val timestamp = chunk.timestamp.toLong()
                val age = System.currentTimeMillis() - timestamp
                val recencyScore = 1.0f / (1.0f + age / 86400000.0f)  // Decay over days
                RetrievalResult(chunk, recencyScore, RetrievalStrategy.RECENCY)
            } catch (e: NumberFormatException) {
                // Skip chunks with invalid timestamps
                null
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Provide feedback to improve future retrievals (MemRL learning)
     * @param chunkIds List of chunk IDs that were retrieved
     * @param success Whether the retrieval was helpful
     */
    @Throws(Exception::class)
    fun provideFeedback(chunkIds: List<String>, success: Boolean) {
        for (chunkId in chunkIds) {
            val chunk = chunkIndex[chunkId]
            if (chunk != null) {
                chunk.updateQValue(success, DEFAULT_LEARNING_RATE)
                Log.d(TAG, "Updated Q-value for chunk $chunkId: ${chunk.qValue}")
            }
        }
        saveChunks()
    }

    /**
     * Get MemRL statistics
     */
    fun getMemRLStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()

        if (chunks.isEmpty()) {
            stats["avg_q_value"] = 0.0f
            stats["success_rate"] = 0.0f
            stats["total_retrievals"] = 0
            return stats
        }

        var sumQValue = 0.0f
        var totalRetrievals = 0
        var totalSuccesses = 0

        for (chunk in chunks) {
            sumQValue += chunk.qValue
            totalRetrievals += chunk.retrievalCount
            totalSuccesses += chunk.successCount
        }

        stats["avg_q_value"] = sumQValue / chunks.size
        stats["success_rate"] = if (totalRetrievals > 0) totalSuccesses.toFloat() / totalRetrievals else 0.0f
        stats["total_retrievals"] = totalRetrievals
        stats["total_chunks"] = chunks.size

        return stats
    }

    /**
     * Get a read-only view of all chunks in this store.
     */
    fun getChunks(): List<TextChunk> = chunks.toList()

    /**
     * Remove a specific chunk by ID.
     * @return true if the chunk was found and removed
     */
    @Throws(Exception::class)
    fun removeChunk(chunkId: String): Boolean {
        val chunk = chunkIndex.remove(chunkId) ?: return false
        chunks.remove(chunk)
        saveChunks()
        Log.d(TAG, "Removed chunk: $chunkId for AI: $aiId")
        return true
    }

    /**
     * Clear all chunks
     */
    @Throws(Exception::class)
    fun clear() {
        chunks.clear()
        chunkIndex.clear()
        storage.delete("rag_chunks_$aiId")
        Log.d(TAG, "Cleared RAG store for AI: $aiId")
    }

    // Helper methods

    private fun generateChunkId(content: String, source: String): String =
        try {
            val input = content.substring(0, min(100, content.length)) + source + System.currentTimeMillis()
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(input.toByteArray(StandardCharsets.UTF_8))
            val hexString = StringBuilder()
            for (i in 0 until min(8, hash.size)) {
                val hex = Integer.toHexString(0xff and hash[i].toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString()
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }

    private fun estimateTokens(text: String): Int =
        // Rough estimate: ~4 characters per token
        text.length / 4

    private fun generateEmbedding(text: String): FloatArray {
        // Simplified TF-IDF-like embedding (100 dimensions)
        // In production, use proper embedding model
        val words = text.lowercase().split("\\s+".toRegex())
        val embedding = FloatArray(100)

        for (word in words) {
            val hash = abs(word.hashCode() % 100)
            embedding[hash] += 1.0f
        }

        // Normalize
        var norm = 0.0f
        for (value in embedding) {
            norm += value * value
        }
        norm = sqrt(norm)

        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f

        var dot = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }

        return dot  // Already normalized
    }

    @Throws(Exception::class)
    private fun saveChunks() {
        val chunksArray = JSONArray()

        for (chunk in chunks) {
            val chunkObj = JSONObject().apply {
                put("chunkId", chunk.chunkId)
                put("content", chunk.content)
                put("source", chunk.source)
                put("sourceType", chunk.sourceType)
                put("timestamp", chunk.timestamp)
                put("tokenCount", chunk.tokenCount)
                put("qValue", chunk.qValue.toDouble())
                put("retrievalCount", chunk.retrievalCount)
                put("successCount", chunk.successCount)

                // Save metadata
                if (chunk.metadata.isNotEmpty()) {
                    val metaObj = JSONObject()
                    for ((key, value) in chunk.metadata) {
                        metaObj.put(key, value)
                    }
                    put("metadata", metaObj)
                }

                // Save embedding
                chunk.embedding?.let { emb ->
                    val embArray = JSONArray()
                    for (value in emb) {
                        embArray.put(value.toDouble())
                    }
                    put("embedding", embArray)
                }
            }

            chunksArray.put(chunkObj)
        }

        storage.store("rag_chunks_$aiId", chunksArray.toString())
    }

    private fun loadChunks() {
        try {
            val data = storage.retrieve("rag_chunks_$aiId") ?: return

            val chunksArray = JSONArray(data)
            for (i in 0 until chunksArray.length()) {
                val chunkObj = chunksArray.getJSONObject(i)

                val chunk = TextChunk(
                    chunkObj.getString("chunkId"),
                    chunkObj.getString("content"),
                    chunkObj.getString("source"),
                    chunkObj.getString("sourceType"),
                    chunkObj.getString("timestamp"),
                    chunkObj.getInt("tokenCount")
                )

                // Load MemRL values
                if (chunkObj.has("qValue")) {
                    val savedQ = chunkObj.getDouble("qValue").toFloat()
                    val savedRetrieval = chunkObj.optInt("retrievalCount", 0)
                    val savedSuccess = chunkObj.optInt("successCount", 0)
                    chunk.restoreMemRLState(savedQ, savedRetrieval, savedSuccess)
                }

                // Load metadata
                if (chunkObj.has("metadata")) {
                    val metaObj = chunkObj.getJSONObject("metadata")
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        chunk.metadata[key] = metaObj.get(key)
                    }
                }

                // Load embedding
                if (chunkObj.has("embedding")) {
                    val embArray = chunkObj.getJSONArray("embedding")
                    val embedding = FloatArray(embArray.length()) { j ->
                        embArray.getDouble(j).toFloat()
                    }
                    chunk.embedding = embedding
                }

                chunks.add(chunk)
                chunkIndex[chunk.chunkId] = chunk
            }

            Log.d(TAG, "Loaded ${chunks.size} chunks for AI: $aiId")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chunks", e)
        }
    }

    companion object {
        private const val TAG = "RAGStore"
        private const val DEFAULT_TOP_K = 10
        private const val DEFAULT_LEARNING_RATE = 0.1f
        private const val MAX_CHUNK_SIZE = 512  // tokens
    }
}
