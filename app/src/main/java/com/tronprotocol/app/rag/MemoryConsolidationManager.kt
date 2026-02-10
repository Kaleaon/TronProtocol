package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject
import java.util.Calendar

/**
 * Memory Consolidation Manager
 *
 * Inspired by neuroscience research on sleep-based memory consolidation:
 * - Reorganizes memories during idle/rest periods
 * - Strengthens important memories
 * - Weakens or removes low-value memories
 * - Creates connections between related memories
 * - Optimizes retrieval efficiency
 *
 * Similar to how the brain consolidates memories during sleep, this system
 * runs during device idle time to improve the RAG system's performance.
 *
 * Based on research concepts:
 * - Memory replay and consolidation (Wilson & McNaughton, 1994)
 * - Systems consolidation theory
 * - Active forgetting mechanisms
 */
class MemoryConsolidationManager @Throws(Exception::class) constructor(
    private val context: Context
) {
    private val storage: SecureStorage = SecureStorage(context)
    private var totalConsolidations: Int = 0
    private var memoriesStrengthened: Int = 0
    private var memoriesWeakened: Int = 0
    private var memoriesForgotten: Int = 0

    init {
        loadStats()
    }

    /**
     * Perform memory consolidation on a RAG store
     * This should be called during idle/rest periods (e.g., nighttime, charging)
     *
     * @param ragStore The RAG store to consolidate
     * @return ConsolidationResult with statistics
     */
    fun consolidate(ragStore: RAGStore): ConsolidationResult {
        Log.d(TAG, "Starting memory consolidation...")
        val startTime = System.currentTimeMillis()

        val result = ConsolidationResult()

        try {
            // Phase 1: Strengthen important memories
            result.strengthened = strengthenImportantMemories(ragStore)
            memoriesStrengthened += result.strengthened

            // Phase 2: Weaken low-performing memories
            result.weakened = weakenUnusedMemories(ragStore)
            memoriesWeakened += result.weakened

            // Phase 3: Remove very low-value memories (active forgetting)
            result.forgotten = forgetLowValueMemories(ragStore)
            memoriesForgotten += result.forgotten

            // Phase 4: Create connections between related memories
            result.connections = createMemoryConnections(ragStore)

            // Phase 5: Optimize chunk organization
            result.optimized = optimizeChunkOrganization(ragStore)

            totalConsolidations++
            result.duration = System.currentTimeMillis() - startTime
            result.success = true

            saveStats()

            Log.d(TAG, "Consolidation complete: $result")

        } catch (e: Exception) {
            Log.e(TAG, "Error during consolidation", e)
            result.success = false
        }

        return result
    }

    /**
     * Phase 1: Strengthen memories with high Q-values (successful retrievals)
     * Similar to memory replay during sleep — gives positive feedback to high-performing chunks.
     */
    private fun strengthenImportantMemories(ragStore: RAGStore): Int {
        Log.d(TAG, "Strengthening important memories...")

        val chunks = ragStore.getChunks()
        val highPerformers = chunks.filter { it.qValue > STRENGTHEN_THRESHOLD }

        if (highPerformers.isNotEmpty()) {
            ragStore.provideFeedback(
                highPerformers.map { it.chunkId },
                true
            )
        }

        Log.d(TAG, "Strengthened ${highPerformers.size} memories")
        return highPerformers.size
    }

    /**
     * Phase 2: Weaken memories with low retrieval success
     * Similar to synaptic scaling during sleep — gives negative feedback to underperforming chunks.
     */
    private fun weakenUnusedMemories(ragStore: RAGStore): Int {
        Log.d(TAG, "Weakening unused memories...")

        val chunks = ragStore.getChunks()
        val lowPerformers = chunks.filter {
            it.retrievalCount > 0 && it.qValue < CONSOLIDATION_THRESHOLD
        }

        if (lowPerformers.isNotEmpty()) {
            ragStore.provideFeedback(
                lowPerformers.map { it.chunkId },
                false
            )
        }

        Log.d(TAG, "Weakened ${lowPerformers.size} memories")
        return lowPerformers.size
    }

    /**
     * Phase 3: Remove very low-value memories (active forgetting)
     * Similar to how the brain selectively forgets unimportant information.
     * Removes chunks with very low Q-values that have had enough retrievals to be judged.
     */
    private fun forgetLowValueMemories(ragStore: RAGStore): Int {
        Log.d(TAG, "Forgetting low-value memories...")

        val chunks = ragStore.getChunks()
        val forgettable = chunks.filter {
            it.retrievalCount >= MIN_RETRIEVALS_FOR_FORGET && it.qValue < FORGET_THRESHOLD
        }.sortedBy { it.qValue }
            .take(MAX_FORGET_PER_CYCLE)

        var forgotten = 0
        for (chunk in forgettable) {
            try {
                if (ragStore.removeChunk(chunk.chunkId)) {
                    forgotten++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove chunk ${chunk.chunkId}", e)
            }
        }

        Log.d(TAG, "Forgot $forgotten low-value memories")
        return forgotten
    }

    /**
     * Phase 4: Create connections between semantically related memories
     * Similar to how sleep strengthens associations.
     * Uses semantic retrieval to find related chunks and stores connection metadata.
     */
    private fun createMemoryConnections(ragStore: RAGStore): Int {
        Log.d(TAG, "Creating memory connections...")

        val chunks = ragStore.getChunks()
        var connections = 0

        for (chunk in chunks) {
            try {
                val related = ragStore.retrieve(
                    chunk.content,
                    RetrievalStrategy.SEMANTIC,
                    CONNECTION_CANDIDATES + 1
                )
                val relatedIds = related
                    .filter { it.chunk.chunkId != chunk.chunkId && it.score > CONNECTION_SIMILARITY_THRESHOLD }
                    .take(MAX_CONNECTIONS_PER_CHUNK)
                    .map { it.chunk.chunkId }

                if (relatedIds.isNotEmpty()) {
                    chunk.addMetadata("connected_chunks", relatedIds.joinToString(","))
                    connections += relatedIds.size
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create connections for chunk ${chunk.chunkId}", e)
            }
        }

        Log.d(TAG, "Created $connections memory connections")
        return connections
    }

    /**
     * Phase 5: Optimize chunk organization for faster retrieval
     * Similar to memory reorganization during sleep.
     * Tags chunks with a consolidation timestamp and updates importance metadata
     * based on current Q-value standings.
     */
    private fun optimizeChunkOrganization(ragStore: RAGStore): Int {
        Log.d(TAG, "Optimizing chunk organization...")

        val chunks = ragStore.getChunks()
        val now = System.currentTimeMillis().toString()
        var optimized = 0

        for (chunk in chunks) {
            chunk.addMetadata("last_consolidated", now)
            val tier = when {
                chunk.qValue >= STRENGTHEN_THRESHOLD -> "high"
                chunk.qValue >= CONSOLIDATION_THRESHOLD -> "medium"
                else -> "low"
            }
            chunk.addMetadata("importance_tier", tier)
            optimized++
        }

        // Persist the updated metadata
        try {
            ragStore.provideFeedback(emptyList(), false)
        } catch (_: Exception) {
            // provideFeedback with empty list just triggers a save
        }

        Log.d(TAG, "Optimized $optimized chunks")
        return optimized
    }

    /**
     * Get consolidation statistics
     */
    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>(
            "total_consolidations" to totalConsolidations,
            "memories_strengthened" to memoriesStrengthened,
            "memories_weakened" to memoriesWeakened,
            "memories_forgotten" to memoriesForgotten
        )

        if (totalConsolidations > 0) {
            stats["avg_strengthened_per_consolidation"] =
                memoriesStrengthened / totalConsolidations
            stats["avg_forgotten_per_consolidation"] =
                memoriesForgotten / totalConsolidations
        }

        return stats
    }

    /**
     * Check if it's a good time for consolidation
     * (nighttime, device charging, low activity, etc.)
     */
    fun isConsolidationTime(): Boolean {
        // Get current hour (0-23)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // Consider nighttime (1 AM - 5 AM) as consolidation time
        val isNighttime = hour in 1..5

        // In full implementation, also check:
        // - Device is charging
        // - Device is idle (no user activity)
        // - Screen is off
        // - Wi-Fi connected (if needed)

        return isNighttime
    }

    @Throws(Exception::class)
    private fun saveStats() {
        val stats = getStats()
        val statsObj = JSONObject(stats)
        storage.store(STATS_KEY, statsObj.toString())
    }

    private fun loadStats() {
        try {
            val data = storage.retrieve(STATS_KEY) ?: return
            val statsObj = JSONObject(data)
            totalConsolidations = statsObj.optInt("total_consolidations", 0)
            memoriesStrengthened = statsObj.optInt("memories_strengthened", 0)
            memoriesWeakened = statsObj.optInt("memories_weakened", 0)
            memoriesForgotten = statsObj.optInt("memories_forgotten", 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading stats", e)
        }
    }

    /**
     * Result of a consolidation operation
     */
    class ConsolidationResult {
        @JvmField var success: Boolean = false
        @JvmField var strengthened: Int = 0
        @JvmField var weakened: Int = 0
        @JvmField var forgotten: Int = 0
        @JvmField var connections: Int = 0
        @JvmField var optimized: Int = 0
        @JvmField var duration: Long = 0

        override fun toString(): String =
            "ConsolidationResult{" +
                    "success=" + success +
                    ", strengthened=" + strengthened +
                    ", weakened=" + weakened +
                    ", forgotten=" + forgotten +
                    ", connections=" + connections +
                    ", optimized=" + optimized +
                    ", duration=" + duration + "ms" +
                    '}'
    }

    companion object {
        private const val TAG = "MemoryConsolidation"
        private const val CONSOLIDATION_THRESHOLD = 0.3f  // Q-value below which chunks are weakened
        private const val STRENGTHEN_THRESHOLD = 0.7f     // Q-value above which chunks are strengthened
        private const val FORGET_THRESHOLD = 0.15f        // Q-value below which chunks may be removed
        private const val MIN_RETRIEVALS_FOR_FORGET = 3   // Minimum retrievals before a chunk can be forgotten
        private const val MAX_FORGET_PER_CYCLE = 5        // Max chunks to forget per consolidation cycle
        private const val CONNECTION_CANDIDATES = 3       // Candidates to consider for connections
        private const val MAX_CONNECTIONS_PER_CHUNK = 3   // Max connections per chunk
        private const val CONNECTION_SIMILARITY_THRESHOLD = 0.3f // Min similarity for a connection
        private const val MAX_CONSOLIDATION_ROUNDS = 5
        private const val STATS_KEY = "consolidation_stats"
    }
}
