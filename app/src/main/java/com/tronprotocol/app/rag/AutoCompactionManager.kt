package com.tronprotocol.app.rag

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-Compaction Manager — reactive context overflow management.
 *
 * Inspired by OpenClaw's auto-compaction system:
 * - Detects context overflow before it causes API errors
 * - Automatically summarizes old conversation turns
 * - Preserves recent context while compacting history
 * - Tracks token usage across sessions
 * - Triggers compaction at configurable thresholds
 * - Integrates with RAGStore to compact/summarize stored memories
 *
 * Unlike the existing MemoryConsolidationManager (which runs on a schedule
 * during idle periods), this manager reacts to actual memory pressure
 * in real-time, triggered by approaching context window limits.
 */
class AutoCompactionManager(
    private val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    private val compactionThreshold: Float = DEFAULT_COMPACTION_THRESHOLD,
    private val preserveRecentCount: Int = DEFAULT_PRESERVE_RECENT
) {

    /** Result of a compaction operation. */
    data class CompactionResult(
        val success: Boolean,
        val chunksBeforeCompaction: Int,
        val chunksAfterCompaction: Int,
        val tokensRecovered: Int,
        val summariesCreated: Int,
        val durationMs: Long,
        val error: String? = null
    ) {
        val compressionRatio: Float
            get() = if (chunksBeforeCompaction > 0) {
                chunksAfterCompaction.toFloat() / chunksBeforeCompaction
            } else 0f
    }

    /** Token usage snapshot. */
    data class TokenUsage(
        val totalTokens: Int,
        val chunkCount: Int,
        val maxTokens: Int,
        val utilizationPercent: Float,
        val needsCompaction: Boolean
    )

    // State
    private val compacting = AtomicBoolean(false)
    private var totalCompactions = 0
    private var totalTokensRecovered = 0
    private var lastCompactionTime = 0L

    /**
     * Check current token usage and determine if compaction is needed.
     */
    fun checkUsage(ragStore: RAGStore): TokenUsage {
        val chunks = ragStore.getChunks()
        val totalTokens = chunks.sumOf { it.tokenCount }
        val utilization = totalTokens.toFloat() / maxContextTokens
        val needsCompaction = utilization >= compactionThreshold

        return TokenUsage(
            totalTokens = totalTokens,
            chunkCount = chunks.size,
            maxTokens = maxContextTokens,
            utilizationPercent = utilization * 100,
            needsCompaction = needsCompaction
        )
    }

    /**
     * Trigger compaction if the threshold is exceeded.
     * Returns null if compaction is not needed or already in progress.
     */
    fun compactIfNeeded(ragStore: RAGStore): CompactionResult? {
        val usage = checkUsage(ragStore)

        if (!usage.needsCompaction) {
            return null
        }

        return compact(ragStore)
    }

    /**
     * Force a compaction operation, regardless of current usage.
     */
    fun compact(ragStore: RAGStore): CompactionResult {
        if (!compacting.compareAndSet(false, true)) {
            return CompactionResult(
                success = false, chunksBeforeCompaction = 0, chunksAfterCompaction = 0,
                tokensRecovered = 0, summariesCreated = 0, durationMs = 0,
                error = "Compaction already in progress"
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val chunks = ragStore.getChunks()
            val totalTokensBefore = chunks.sumOf { it.tokenCount }
            val chunkCountBefore = chunks.size

            if (chunks.size <= preserveRecentCount) {
                return CompactionResult(
                    success = true, chunksBeforeCompaction = chunkCountBefore,
                    chunksAfterCompaction = chunkCountBefore, tokensRecovered = 0,
                    summariesCreated = 0, durationMs = System.currentTimeMillis() - startTime
                )
            }

            // Sort chunks by timestamp (oldest first)
            val sorted = chunks.sortedBy { it.timestamp.toLongOrNull() ?: 0L }

            // Preserve recent chunks, compact older ones
            val toCompact = sorted.dropLast(preserveRecentCount)
            val toPreserve = sorted.takeLast(preserveRecentCount)

            // Group older chunks by source type for summarization
            val groupedBySource = toCompact.groupBy { it.sourceType }
            var summariesCreated = 0

            for ((sourceType, sourceChunks) in groupedBySource) {
                // Create summary groups (batch similar chunks)
                val batches = batchChunks(sourceChunks, MAX_SUMMARY_BATCH_TOKENS)

                for (batch in batches) {
                    if (batch.size <= 1) continue

                    // Create a summary of the batch
                    val summary = summarizeBatch(batch)
                    val avgQValue = batch.map { it.qValue }.average().toFloat()

                    // Remove individual chunks
                    for (chunk in batch) {
                        try {
                            ragStore.removeChunk(chunk.chunkId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remove chunk ${chunk.chunkId} during compaction", e)
                        }
                    }

                    // Add summary chunk
                    try {
                        val summaryId = ragStore.addChunk(
                            summary,
                            "compaction_summary",
                            sourceType,
                            mapOf(
                                "compacted_from" to batch.size,
                                "original_tokens" to batch.sumOf { it.tokenCount },
                                "avg_q_value" to avgQValue,
                                "compaction_time" to System.currentTimeMillis()
                            )
                        )

                        // Provide positive feedback to preserve Q-value
                        if (avgQValue > 0.5f) {
                            ragStore.provideFeedback(listOf(summaryId), true)
                        }

                        summariesCreated++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create summary chunk during compaction", e)
                    }
                }
            }

            // Also remove very low Q-value chunks that aren't worth summarizing
            val lowQChunks = toCompact.filter { it.qValue < LOW_Q_THRESHOLD && it.retrievalCount > 3 }
            for (chunk in lowQChunks) {
                try {
                    ragStore.removeChunk(chunk.chunkId)
                } catch (e: Exception) {
                    // Already removed in batch processing
                }
            }

            val chunksAfter = ragStore.getChunks()
            val totalTokensAfter = chunksAfter.sumOf { it.tokenCount }
            val tokensRecovered = totalTokensBefore - totalTokensAfter
            val durationMs = System.currentTimeMillis() - startTime

            totalCompactions++
            totalTokensRecovered += tokensRecovered
            lastCompactionTime = System.currentTimeMillis()

            Log.d(TAG, "Compaction complete: ${chunkCountBefore} -> ${chunksAfter.size} chunks, " +
                    "$tokensRecovered tokens recovered, $summariesCreated summaries created, " +
                    "${durationMs}ms")

            return CompactionResult(
                success = true,
                chunksBeforeCompaction = chunkCountBefore,
                chunksAfterCompaction = chunksAfter.size,
                tokensRecovered = tokensRecovered,
                summariesCreated = summariesCreated,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Compaction failed", e)
            return CompactionResult(
                success = false, chunksBeforeCompaction = 0, chunksAfterCompaction = 0,
                tokensRecovered = 0, summariesCreated = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = "Compaction failed: ${e.message}"
            )
        } finally {
            compacting.set(false)
        }
    }

    /**
     * Build a compacted prompt from RAG store contents.
     * Used by ModelFailoverManager when context overflow occurs.
     */
    fun buildCompactedPrompt(ragStore: RAGStore, originalPrompt: String): String {
        // Force compact to free up space
        compact(ragStore)

        // Retrieve only the most relevant memories for the prompt
        val results = ragStore.retrieve(originalPrompt, RetrievalStrategy.MEMRL, 5)
        val context = results.joinToString("\n") { it.chunk.content.take(200) }

        return if (context.isNotEmpty()) {
            "[Compacted Context]\n$context\n\n[Query]\n$originalPrompt"
        } else {
            originalPrompt
        }
    }

    /**
     * Get compaction statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_compactions" to totalCompactions,
        "total_tokens_recovered" to totalTokensRecovered,
        "last_compaction_time" to lastCompactionTime,
        "is_compacting" to compacting.get(),
        "max_context_tokens" to maxContextTokens,
        "compaction_threshold" to compactionThreshold,
        "preserve_recent" to preserveRecentCount
    )

    // -- Internal helpers --

    /**
     * Group chunks into batches that fit within a token budget.
     */
    private fun batchChunks(chunks: List<TextChunk>, maxBatchTokens: Int): List<List<TextChunk>> {
        val batches = mutableListOf<List<TextChunk>>()
        var currentBatch = mutableListOf<TextChunk>()
        var currentTokens = 0

        for (chunk in chunks) {
            if (currentTokens + chunk.tokenCount > maxBatchTokens && currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentTokens = 0
            }
            currentBatch.add(chunk)
            currentTokens += chunk.tokenCount
        }

        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        return batches
    }

    /**
     * Create a summary of a batch of chunks.
     * Uses extractive summarization (key sentence selection).
     */
    private fun summarizeBatch(chunks: List<TextChunk>): String {
        if (chunks.isEmpty()) return ""
        if (chunks.size == 1) return chunks[0].content

        // Score sentences by:
        // 1. Q-value of parent chunk (higher = more important)
        // 2. Position (first sentences are usually more informative)
        // 3. Length (very short sentences are usually less informative)
        val scoredSentences = mutableListOf<Pair<String, Float>>()

        for (chunk in chunks) {
            val sentences = chunk.content.split(Regex("[.!?]+"))
                .map { it.trim() }
                .filter { it.length > 10 }

            for ((index, sentence) in sentences.withIndex()) {
                val positionScore = 1.0f / (1.0f + index * 0.3f) // Earlier = better
                val qScore = chunk.qValue
                val lengthScore = if (sentence.length > 30) 1.0f else 0.5f
                val totalScore = positionScore * 0.3f + qScore * 0.5f + lengthScore * 0.2f
                scoredSentences.add(sentence to totalScore)
            }
        }

        // Select top sentences
        val selected = scoredSentences
            .sortedByDescending { it.second }
            .take(MAX_SUMMARY_SENTENCES)
            .map { it.first }

        val sourceInfo = "[Compacted from ${chunks.size} memories, " +
                "${chunks.sumOf { it.tokenCount }} tokens]"

        return "$sourceInfo\n${selected.joinToString(". ")}."
    }

    companion object {
        private const val TAG = "AutoCompactionManager"
        const val DEFAULT_MAX_CONTEXT_TOKENS = 100_000
        const val DEFAULT_COMPACTION_THRESHOLD = 0.75f
        const val DEFAULT_PRESERVE_RECENT = 20
        private const val MAX_SUMMARY_BATCH_TOKENS = 2000
        private const val MAX_SUMMARY_SENTENCES = 5
        private const val LOW_Q_THRESHOLD = 0.15f
    }
}
