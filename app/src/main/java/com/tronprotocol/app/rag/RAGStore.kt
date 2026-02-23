package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import com.tronprotocol.app.frontier.FrontierDynamicsManager
import kotlin.math.abs
import kotlin.math.max
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
    private val aiId: String,
    private val telemetrySink: RetrievalTelemetrySink = LocalJsonlRetrievalMetricsSink(context, aiId)
) {
    private val storage: SecureStorage = SecureStorage(context)
    private val chunks: MutableList<TextChunk> = mutableListOf()
    private val chunkIndex: MutableMap<String, TextChunk> = mutableMapOf()

    // MiniRAG-inspired knowledge graph for entity-aware retrieval
    val knowledgeGraph: KnowledgeGraph = KnowledgeGraph(context, aiId)
    private val entityExtractor: EntityExtractor = EntityExtractor()
    private val ntsScoringEngine: NeuralTemporalScoringEngine = NeuralTemporalScoringEngine()

    /** Optional Frontier Dynamics STLE manager for accessibility-aware retrieval. */
    var frontierDynamicsManager: FrontierDynamicsManager? = null

    /**
     * Tunable learning rate for Q-value updates.
     * Updated by [SleepCycleOptimizer] during consolidation sleep cycles.
     * Defaults to the original constant (0.1f).
     */
    @Volatile
    var learningRate: Float = DEFAULT_LEARNING_RATE

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

        // Neural Temporal Stack (NTS) memory-stage annotation + MISE-inspired signals
        val stage = ntsScoringEngine.assignStage(
            content = content,
            sourceType = sourceType,
            baseImportance = metadata?.get("importance")?.toString()?.toFloatOrNull()
        )
        MemoryStage.assign(chunk, stage)
        chunk.addMetadata("novelty", ntsScoringEngine.estimateNovelty(content))
        chunk.addMetadata("emotional_salience", ntsScoringEngine.estimateEmotionalSalience(content))

        chunks.add(chunk)
        chunkIndex[chunkId] = chunk

        // Evict lowest-Q-value chunks if store exceeds maximum capacity
        evictIfNeeded()

        // Extract entities and populate knowledge graph (MiniRAG-inspired)
        var graphModified = false
        try {
            val extraction = entityExtractor.extract(content)
            val entityIds = mutableListOf<String>()

            for (entity in extraction.entities) {
                val entityId = knowledgeGraph.addEntity(entity.name, entity.entityType, entity.context)
                entityIds.add(entityId)
                graphModified = true
            }

            if (entityIds.isNotEmpty()) {
                val summary = content.substring(0, min(100, content.length))
                knowledgeGraph.addChunkNode(chunkId, summary, entityIds)
                chunk.addMetadata("entity_count", entityIds.size)
            }

            for (rel in extraction.relationships) {
                val sourceId = "entity_${rel.sourceEntity.lowercase().trim()}"
                val targetId = "entity_${rel.targetEntity.lowercase().trim()}"
                knowledgeGraph.addRelationship(sourceId, targetId, rel.relationship, rel.strength)
                graphModified = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Entity extraction failed for chunk $chunkId", e)
        } finally {
            // Guarantee graph is saved even if extraction partially fails
            if (graphModified) {
                try {
                    knowledgeGraph.save()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save knowledge graph after entity extraction", e)
                }
            }
        }

        saveChunks()
        Log.d(TAG, "Added chunk: $chunkId for AI: $aiId")

        return chunkId
    }

    /**
     * Retrieve relevant chunks using specified strategy
     */
    fun retrieve(query: String, strategy: RetrievalStrategy, topK: Int): List<RetrievalResult> =
        run {
            val start = System.currentTimeMillis()
            val rawResults = when (strategy) {
            RetrievalStrategy.SEMANTIC -> retrieveSemantic(query, topK)
            RetrievalStrategy.KEYWORD -> retrieveKeyword(query, topK)
            RetrievalStrategy.HYBRID -> retrieveHybrid(query, topK)
            RetrievalStrategy.RECENCY -> retrieveRecency(query, topK)
            RetrievalStrategy.MEMRL -> retrieveMemRL(query, topK)
            RetrievalStrategy.RELEVANCE_DECAY -> retrieveRelevanceDecay(query, topK)
            RetrievalStrategy.GRAPH -> retrieveGraph(query, topK)
            RetrievalStrategy.FRONTIER_AWARE -> retrieveFrontierAware(query, topK)
            RetrievalStrategy.NTS_CASCADE -> retrieveNtsCascade(query, topK)
            }

            val elapsed = System.currentTimeMillis() - start
            val enriched = enrichResultsWithDiagnostics(rawResults, strategy)

            recordTelemetry(strategy, elapsed, topK, enriched)
            enriched
        }

    private fun recordTelemetry(
        strategy: RetrievalStrategy,
        latencyMs: Long,
        topK: Int,
        results: List<RetrievalResult>
    ) {
        try {
            telemetrySink.record(
                RetrievalTelemetryEvent(
                    timestampMs = System.currentTimeMillis(),
                    aiId = aiId,
                    strategy = strategy.name,
                    latencyMs = latencyMs,
                    resultCount = results.size,
                    topK = topK,
                    topScore = results.maxOfOrNull { it.score } ?: 0.0f,
                    avgScore = if (results.isNotEmpty()) results.map { it.score }.average().toFloat() else 0.0f
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Telemetry capture failed for strategy=${strategy.name}", t)
        }
    }

    private fun enrichResultsWithDiagnostics(
        rawResults: List<RetrievalResult>,
        strategy: RetrievalStrategy
    ): List<RetrievalResult> {
        val distribution = computeDistribution(rawResults.map { it.score })
        return rawResults.map { result ->
            result.copy(
                strategyId = strategy.name,
                scoreDistribution = distribution,
                stageSource = result.chunk.metadata["nts_stage"]?.toString() ?: result.chunk.sourceType
            )
        }
    }

    private fun computeDistribution(scores: List<Float>): ScoreDistribution? {
        if (scores.isEmpty()) {
            return null
        }
        val minScore = scores.minOrNull() ?: 0.0f
        val maxScore = scores.maxOrNull() ?: 0.0f
        val mean = scores.average().toFloat()
        val variance = scores.map { (it - mean) * (it - mean) }.average().toFloat()
        return ScoreDistribution(minScore, maxScore, mean, sqrt(variance))
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
     * NTS cascade retrieval.
     *
     * Starts from semantic candidates, then re-ranks using stage durability,
     * recency within stage TTL, and learned MemRL utility.
     */
    private fun retrieveNtsCascade(query: String, topK: Int): List<RetrievalResult> {
        val queryEmbedding = generateEmbedding(query)
        val now = System.currentTimeMillis()

        return chunks
            .filter { it.embedding != null }
            .map { chunk ->
                val semanticSimilarity = cosineSimilarity(queryEmbedding, chunk.embedding!!)
                val stageScores = ntsScoringEngine.scoreForRetrieval(chunk, semanticSimilarity, now)
                RetrievalResult(chunk, stageScores.aggregate, RetrievalStrategy.NTS_CASCADE)
            }
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
     * Relevance decay retrieval - combines semantic similarity with time decay.
     * Balances how relevant content is with how recent it is, preventing
     * stale information from dominating results.
     */
    private fun retrieveRelevanceDecay(query: String, topK: Int): List<RetrievalResult> {
        val queryEmbedding = generateEmbedding(query)

        val results = chunks.mapNotNull { chunk ->
            val embedding = chunk.embedding ?: return@mapNotNull null

            try {
                val semanticScore = cosineSimilarity(queryEmbedding, embedding)
                val timestamp = chunk.timestamp.toLong()
                val ageInDays = (System.currentTimeMillis() - timestamp) / 86400000.0f

                // Exponential decay: relevance halves every 30 days
                val decayFactor = Math.pow(0.5, (ageInDays / RELEVANCE_DECAY_HALF_LIFE_DAYS).toDouble()).toFloat()

                // Weighted combination: 60% semantic, 40% recency-decayed
                val combinedScore = 0.6f * semanticScore + 0.4f * decayFactor

                RetrievalResult(chunk, combinedScore, RetrievalStrategy.RELEVANCE_DECAY)
            } catch (e: NumberFormatException) {
                null
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Graph-based topology retrieval (MiniRAG-inspired, arXiv:2501.06713).
     *
     * Uses the heterogeneous knowledge graph for entity-aware retrieval:
     * 1. Extract entities from query
     * 2. Find matching entity nodes in the graph
     * 3. Traverse entity-chunk edges (1-2 hops)
     * 4. Score by topology (node degree, path quality)
     * 5. Combine with semantic scores for final ranking
     *
     * Falls back to HYBRID retrieval if graph has insufficient data.
     */
    private fun retrieveGraph(query: String, topK: Int): List<RetrievalResult> {
        // Extract entities from the query
        val extraction = entityExtractor.extract(query)
        val queryEntityNames = extraction.entities.map { it.name }

        // Also use raw query tokens as potential entity matches
        val queryTokens = query.split("\\s+".toRegex())
            .filter { it.length > 3 }
            .map { it.replace("[^a-zA-Z0-9]".toRegex(), "") }
            .filter { it.isNotEmpty() }

        val allQueryTerms = (queryEntityNames + queryTokens).distinct()

        if (allQueryTerms.isEmpty() || knowledgeGraph.getEntities().isEmpty()) {
            // Fall back to hybrid if no entities available
            return retrieveHybrid(query, topK)
        }

        // Topology retrieval from knowledge graph
        val graphResults = knowledgeGraph.topologyRetrieve(allQueryTerms, topK * 2)

        if (graphResults.isEmpty()) {
            return retrieveHybrid(query, topK)
        }

        // Get semantic scores for blending
        val semanticResults = retrieveSemantic(query, topK * 2)
        val semanticScoreMap = semanticResults.associate { it.chunk.chunkId to it.score }

        // Combine graph topology scores with semantic scores
        val combinedResults = graphResults.mapNotNull { graphResult ->
            val chunk = chunkIndex[graphResult.chunkId] ?: return@mapNotNull null
            val graphScore = graphResult.score
            val semanticScore = semanticScoreMap.getOrDefault(graphResult.chunkId, 0.0f)

            // 50% graph topology, 50% semantic (MiniRAG uses graph as primary signal)
            val combinedScore = 0.5f * graphScore + 0.5f * semanticScore

            RetrievalResult(chunk, combinedScore, RetrievalStrategy.GRAPH)
        }

        // Also include high-scoring semantic results not found by graph
        val graphChunkIds = graphResults.map { it.chunkId }.toSet()
        val additionalSemantic = semanticResults
            .filter { it.chunk.chunkId !in graphChunkIds }
            .map { RetrievalResult(it.chunk, it.score * 0.4f, RetrievalStrategy.GRAPH) }

        return (combinedResults + additionalSemantic)
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Frontier-aware retrieval (Frontier Dynamics STLE-enhanced).
     *
     * Combines semantic similarity with STLE accessibility scoring.
     * Chunks with higher mu_x (in-distribution) are boosted, while
     * out-of-distribution chunks are penalised. Falls back to HYBRID
     * if the FrontierDynamicsManager is not available or not trained.
     *
     * Score = 0.6 * semantic + 0.4 * mu_x
     *
     * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
     */
    private fun retrieveFrontierAware(query: String, topK: Int): List<RetrievalResult> {
        val fdm = frontierDynamicsManager
        if (fdm == null || !fdm.isReady) {
            // Fall back to hybrid when STLE is unavailable
            return retrieveHybrid(query, topK)
        }

        val queryEmbedding = generateEmbedding(query)

        val results = chunks.mapNotNull { chunk ->
            val embedding = chunk.embedding ?: return@mapNotNull null

            val semanticScore = cosineSimilarity(queryEmbedding, embedding)
            val accessibility = fdm.scoreEmbedding(embedding)
            val muX = accessibility?.muX ?: 0.5f

            // Weighted combination: semantic relevance + accessibility confidence
            val combinedScore = 0.6f * semanticScore + 0.4f * muX

            RetrievalResult(chunk, combinedScore, RetrievalStrategy.FRONTIER_AWARE)
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
                chunk.updateQValue(success, learningRate)
                Log.d(TAG, "Updated Q-value for chunk $chunkId: ${chunk.qValue} (lr=$learningRate)")
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

    fun getAiId(): String = aiId

    @Throws(Exception::class)
    fun addStoreMetadata(key: String, value: String) {
        storage.store("rag_store_meta_${aiId}_$key", value)
    }

    /**
     * Remove a specific chunk by ID.
     * @return true if the chunk was found and removed
     */
    @Throws(Exception::class)
    fun removeChunk(chunkId: String): Boolean {
        val chunk = chunkIndex.remove(chunkId) ?: return false
        chunks.remove(chunk)

        // Also clean up the knowledge graph
        try {
            knowledgeGraph.removeChunkNode(chunkId)
            knowledgeGraph.save()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove chunk from knowledge graph", e)
        }

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

    /**
     * Evict lowest-Q-value chunks when store exceeds MAX_CHUNKS capacity.
     * Prevents unbounded memory growth. Keeps the top-performing chunks.
     */
    private fun evictIfNeeded() {
        if (chunks.size <= MAX_CHUNKS) return

        val evictCount = chunks.size - MAX_CHUNKS
        val toEvict = chunks
            .sortedBy { it.qValue }
            .take(evictCount)

        for (chunk in toEvict) {
            chunks.remove(chunk)
            chunkIndex.remove(chunk.chunkId)
            try {
                knowledgeGraph.removeChunkNode(chunk.chunkId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove evicted chunk from knowledge graph", e)
            }
        }
        if (toEvict.isNotEmpty()) {
            try {
                knowledgeGraph.save()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save knowledge graph after eviction", e)
            }
            Log.d(TAG, "Evicted ${toEvict.size} low-Q-value chunks (store was at ${chunks.size + evictCount})")
        }
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
        // Multi-hash embedding with bigrams for better semantic representation.
        // Uses multiple hash seeds to reduce collisions and includes word bigrams
        // to capture some word-order information.
        // In production, replace with a proper embedding model (e.g., TFLite sentence encoder).
        val embeddingSize = 128
        val embedding = FloatArray(embeddingSize)
        val words = text.lowercase()
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }

        if (words.isEmpty()) return embedding

        // IDF approximation: down-weight very common short words using shared stop words set
        // Unigrams with multiple hashes for better distribution
        for (word in words) {
            val weight = if (word in STOP_WORDS) 0.3f else 1.0f
            val h1 = abs(word.hashCode())
            val h2 = abs(word.hashCode() * 31 + 17)
            val h3 = abs(word.hashCode() * 37 + 53)
            embedding[h1 % embeddingSize] += weight
            embedding[h2 % embeddingSize] += weight * 0.5f
            embedding[h3 % embeddingSize] += weight * 0.25f
        }

        // Bigrams for word-order sensitivity
        for (i in 0 until words.size - 1) {
            val bigram = words[i] + "_" + words[i + 1]
            val bh = abs(bigram.hashCode())
            embedding[bh % embeddingSize] += 0.5f
        }

        // L2 normalize
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
        private val STOP_WORDS = setOf("the", "a", "an", "is", "it", "in", "on", "to", "of", "and", "or", "for", "at", "by")
        private const val DEFAULT_TOP_K = 10
        private const val DEFAULT_LEARNING_RATE = 0.1f
        private const val MAX_CHUNK_SIZE = 512  // tokens
        private const val RELEVANCE_DECAY_HALF_LIFE_DAYS = 30.0
        /** Maximum chunks stored before LRU eviction kicks in. Prevents OOM. */
        private const val MAX_CHUNKS = 10_000
    }
}
