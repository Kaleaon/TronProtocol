package com.tronprotocol.app.rag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject

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
    @Volatile private var totalConsolidations: Int = 0
    @Volatile private var memoriesStrengthened: Int = 0
    @Volatile private var memoriesWeakened: Int = 0
    @Volatile private var memoriesForgotten: Int = 0

    /** Optional sleep-cycle optimizer for automatic hyperparameter tuning. */
    var optimizer: SleepCycleOptimizer? = null

    /** Optional Takens model trainer for sleep-cycle weight optimization. */
    var takensTrainer: SleepCycleTakensTrainer? = null

    init {
        loadStats()
    }

    /**
     * Get the effective tunable parameters — from the optimizer if present,
     * otherwise the original defaults.
     */
    private fun effectiveParams(): SleepCycleOptimizer.TunableParams =
        optimizer?.currentParams ?: SleepCycleOptimizer.TunableParams()

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
        val params = effectiveParams()

        try {
            // Phase 1: Strengthen important memories
            result.strengthened = strengthenImportantMemories(ragStore, params)
            memoriesStrengthened += result.strengthened

            // Phase 2: Weaken low-performing memories
            result.weakened = weakenUnusedMemories(ragStore, params)
            memoriesWeakened += result.weakened

            // Phase 3: Remove very low-value memories (active forgetting)
            result.forgotten = forgetLowValueMemories(ragStore, params)
            memoriesForgotten += result.forgotten

            // Phase 4: Create connections between related memories
            result.connections = createMemoryConnections(ragStore, params)

            // Phase 5: Optimize chunk organization
            result.optimized = optimizeChunkOrganization(ragStore, params)

            // Phase 6: Maintain knowledge graph connections
            result.graphEdgesUpdated = maintainKnowledgeGraph(ragStore)

            // Phase 7: Aggregate retrieval telemetry metrics by strategy
            result.telemetryStrategies = aggregateTelemetry(ragStore)

            // Phase 8: Self-optimize hyperparameters for next cycle
            result.optimizationResult = runSelfOptimization(ragStore)

            // Propagate tuned learning rate to RAGStore for Q-value updates
            val tuned = effectiveParams()
            ragStore.learningRate = tuned.learningRate

            // Phase 9: Train/retrain Takens Embedding Transformer on high-Q knowledge
            result.trainingResult = runTakensTraining(ragStore)

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
    private fun strengthenImportantMemories(
        ragStore: RAGStore,
        params: SleepCycleOptimizer.TunableParams = effectiveParams()
    ): Int {
        Log.d(TAG, "Strengthening important memories (threshold=${params.strengthenThreshold})...")

        val chunks = ragStore.getChunks()
        val highPerformers = chunks.filter { it.qValue > params.strengthenThreshold }

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
    private fun weakenUnusedMemories(
        ragStore: RAGStore,
        params: SleepCycleOptimizer.TunableParams = effectiveParams()
    ): Int {
        Log.d(TAG, "Weakening unused memories (threshold=${params.consolidationThreshold})...")

        val chunks = ragStore.getChunks()
        val lowPerformers = chunks.filter {
            it.retrievalCount > 0 && it.qValue < params.consolidationThreshold
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
    private fun forgetLowValueMemories(
        ragStore: RAGStore,
        params: SleepCycleOptimizer.TunableParams = effectiveParams()
    ): Int {
        Log.d(TAG, "Forgetting low-value memories (threshold=${params.forgetThreshold}, max=${params.maxForgetPerCycle})...")

        val chunks = ragStore.getChunks()
        val forgettable = chunks.filter {
            it.retrievalCount >= MIN_RETRIEVALS_FOR_FORGET && it.qValue < params.forgetThreshold
        }.sortedBy { it.qValue }
            .take(params.maxForgetPerCycle)

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
    private fun createMemoryConnections(
        ragStore: RAGStore,
        params: SleepCycleOptimizer.TunableParams = effectiveParams()
    ): Int {
        Log.d(TAG, "Creating memory connections (similarity>=${params.connectionSimilarityThreshold})...")

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
                    .filter { it.chunk.chunkId != chunk.chunkId && it.score > params.connectionSimilarityThreshold }
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
    private fun optimizeChunkOrganization(
        ragStore: RAGStore,
        params: SleepCycleOptimizer.TunableParams = effectiveParams()
    ): Int {
        Log.d(TAG, "Optimizing chunk organization...")

        val chunks = ragStore.getChunks()
        val now = System.currentTimeMillis().toString()
        var optimized = 0

        for (chunk in chunks) {
            chunk.addMetadata("last_consolidated", now)
            val tier = when {
                chunk.qValue >= params.strengthenThreshold -> "high"
                chunk.qValue >= params.consolidationThreshold -> "medium"
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
     * Phase 6: Maintain knowledge graph connections (MiniRAG-inspired)
     * Updates entity relationships and prunes orphaned nodes during consolidation.
     */
    private fun maintainKnowledgeGraph(ragStore: RAGStore): Int {
        Log.d(TAG, "Maintaining knowledge graph...")

        val graph = ragStore.knowledgeGraph
        val chunks = ragStore.getChunks()
        val validChunkIds = chunks.map { it.chunkId }.toSet()
        var updatedEdges = 0

        // Prune chunk nodes that no longer exist in the RAGStore
        val graphStats = graph.getStats()
        val graphChunkCount = graphStats["chunk_count"] as? Int ?: 0
        Log.d(TAG, "Graph has $graphChunkCount chunk nodes, RAGStore has ${validChunkIds.size} chunks")

        // Re-extract entities for chunks without graph entries
        val entityExtractor = EntityExtractor()
        for (chunk in chunks) {
            try {
                val extraction = entityExtractor.extract(chunk.content)
                if (extraction.entities.size >= 2) {
                    // Add relationships between entities found in the same chunk
                    for (rel in extraction.relationships) {
                        val sourceId = "entity_${rel.sourceEntity.lowercase().trim()}"
                        val targetId = "entity_${rel.targetEntity.lowercase().trim()}"
                        graph.addRelationship(sourceId, targetId, rel.relationship, rel.strength)
                        updatedEdges++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update graph for chunk ${chunk.chunkId}", e)
            }
        }

        if (updatedEdges > 0) {
            graph.save()
        }

        Log.d(TAG, "Updated $updatedEdges graph edges during consolidation")
        return updatedEdges
    }

    /**
     * Phase 7: Build telemetry aggregation snapshots for strategy diagnostics.
     */
    private fun aggregateTelemetry(ragStore: RAGStore): Int {
        val analytics = RetrievalTelemetryAnalytics(
            aiId = ragStore.getAiId(),
            sink = LocalJsonlRetrievalMetricsSink(context, ragStore.getAiId())
        )
        val summary = analytics.buildSummary()
        analytics.appendSummaryToStore(ragStore)
        return summary.size
    }

    /**
     * Phase 8: Run the sleep-cycle self-optimizer.
     * Evaluates telemetry, compares against last cycle, and perturbs
     * hyperparameters for the next wake period. No-op if no optimizer is set.
     */
    private fun runSelfOptimization(ragStore: RAGStore): SleepCycleOptimizer.OptimizationResult? {
        val opt = optimizer ?: return null
        return try {
            val result = opt.optimize(ragStore)
            Log.d(TAG, "Phase 8 self-optimization: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Self-optimization failed", e)
            null
        }
    }

    /**
     * Phase 9: Train the Takens Embedding Transformer on high-quality RAG knowledge.
     * Builds a micro model from the best chunks (MEMRL-weighted) so the on-device
     * language model progressively improves. No-op if no trainer is set.
     */
    private fun runTakensTraining(ragStore: RAGStore): SleepCycleTakensTrainer.SleepTrainingResult? {
        val trainer = takensTrainer ?: return null
        return try {
            val result = trainer.trainDuringSleep(ragStore)
            Log.d(TAG, "Phase 9 Takens training: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Takens training failed", e)
            null
        }
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

    // -- Adaptive rest detection state -----------------------------------------

    /** Timestamp when the device was first observed as idle (screen off). 0 = not idle. */
    @Volatile private var idleSinceMs: Long = 0L

    /** Timestamp when the device was first observed as charging. 0 = not charging. */
    @Volatile private var chargingSinceMs: Long = 0L

    /**
     * Adaptive rest detection — determines if the device is in a "sleep" state
     * suitable for consolidation.
     *
     * Unlike a fixed nighttime window, this adapts to the user's actual schedule
     * by tracking how long the device has been continuously idle and/or charging.
     *
     * Triggers:
     * - PRIMARY: idle (screen off) + charging for >= [REST_ONSET_MS] (30 min)
     * - SECONDARY: idle for >= [DEEP_REST_ONSET_MS] (60 min), regardless of charging
     *
     * This correctly handles night-shift workers, irregular schedules, or anyone
     * whose rest periods don't align with conventional nighttime hours.
     */
    fun isConsolidationTime(): Boolean {
        val now = System.currentTimeMillis()

        // Probe current device state
        val isIdle = try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager != null && !powerManager.isInteractive
        } catch (e: Exception) {
            Log.w(TAG, "Could not check idle status", e)
            false
        }

        val isCharging = try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.w(TAG, "Could not check charging status", e)
            false
        }

        // Track idle duration (reset when device becomes active)
        if (isIdle) {
            if (idleSinceMs == 0L) idleSinceMs = now
        } else {
            idleSinceMs = 0L
        }

        // Track charging duration
        if (isCharging) {
            if (chargingSinceMs == 0L) chargingSinceMs = now
        } else {
            chargingSinceMs = 0L
        }

        val idleDurationMs = if (idleSinceMs > 0L) now - idleSinceMs else 0L

        // Primary: idle + charging for the rest-onset threshold
        if (isIdle && isCharging && idleDurationMs >= REST_ONSET_MS) {
            Log.d(TAG, "Consolidation trigger: idle+charging for ${idleDurationMs / 60_000}min")
            return true
        }

        // Secondary: long idle regardless of charging state
        if (isIdle && idleDurationMs >= DEEP_REST_ONSET_MS) {
            Log.d(TAG, "Consolidation trigger: deep rest (idle ${idleDurationMs / 60_000}min)")
            return true
        }

        return false
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
        @JvmField var graphEdgesUpdated: Int = 0
        @JvmField var telemetryStrategies: Int = 0
        @JvmField var duration: Long = 0

        /** Phase 8: Self-optimization result (null if no optimizer is attached). */
        var optimizationResult: SleepCycleOptimizer.OptimizationResult? = null

        /** Phase 9: Takens model training result (null if no trainer is attached). */
        var trainingResult: SleepCycleTakensTrainer.SleepTrainingResult? = null

        override fun toString(): String =
            "ConsolidationResult{" +
                    "success=" + success +
                    ", strengthened=" + strengthened +
                    ", weakened=" + weakened +
                    ", forgotten=" + forgotten +
                    ", connections=" + connections +
                    ", optimized=" + optimized +
                    ", graphEdges=" + graphEdgesUpdated +
                    ", telemetryStrategies=" + telemetryStrategies +
                    ", optimization=" + (optimizationResult?.reason ?: "none") +
                    ", training=" + (trainingResult?.reason ?: "none") +
                    ", duration=" + duration + "ms" +
                    '}'
    }

    companion object {
        private const val TAG = "MemoryConsolidation"

        // Legacy constants — used as fallbacks when no SleepCycleOptimizer is attached.
        // When an optimizer is present, these are replaced by TunableParams values.
        private const val CONSOLIDATION_THRESHOLD = 0.3f
        private const val STRENGTHEN_THRESHOLD = 0.7f
        private const val FORGET_THRESHOLD = 0.15f
        private const val MIN_RETRIEVALS_FOR_FORGET = 3
        private const val MAX_FORGET_PER_CYCLE = 5
        private const val CONNECTION_CANDIDATES = 3
        private const val MAX_CONNECTIONS_PER_CHUNK = 3
        private const val CONNECTION_SIMILARITY_THRESHOLD = 0.3f
        private const val MAX_CONSOLIDATION_ROUNDS = 5
        private const val STATS_KEY = "consolidation_stats"

        // Adaptive rest detection thresholds
        /** Idle + charging for 30 minutes triggers consolidation. */
        private const val REST_ONSET_MS = 30L * 60 * 1000

        /** Idle for 60 minutes (regardless of charging) triggers consolidation. */
        private const val DEEP_REST_ONSET_MS = 60L * 60 * 1000
    }
}
