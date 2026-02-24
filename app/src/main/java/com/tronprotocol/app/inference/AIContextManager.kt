package com.tronprotocol.app.inference

import android.util.Log
import com.tronprotocol.app.ConversationTurn
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages conversation context for AI inference, implementing a sliding window
 * with automatic summarization of older messages to maintain effective context
 * within token limits.
 *
 * Key responsibilities:
 * - Track conversation turns with token estimates
 * - Compress older context via extractive summarization when window is exceeded
 * - Provide formatted context strings for inference requests
 * - Maintain system prompt + recent turns within budget
 */
class AIContextManager(
    private val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    private val reservedResponseTokens: Int = DEFAULT_RESERVED_RESPONSE_TOKENS,
    private val summaryTriggerRatio: Float = 0.85f
) {
    private val contextEntries = CopyOnWriteArrayList<ContextEntry>()
    private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    private var contextSummary: String? = null
    private var totalEstimatedTokens: Int = 0
    private var summarizationCount: Int = 0

    data class ContextEntry(
        val role: String,
        val content: String,
        val estimatedTokens: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )

    data class ContextWindow(
        val systemPrompt: String,
        val summary: String?,
        val recentTurns: List<ContextEntry>,
        val totalTokens: Int,
        val maxTokens: Int,
        val utilizationPercent: Float,
        val turnCount: Int,
        val summarizedTurnCount: Int
    )

    /**
     * Add a conversation turn to the context.
     */
    fun addTurn(role: String, content: String, metadata: Map<String, String> = emptyMap()) {
        val tokens = estimateTokens(content)
        val entry = ContextEntry(
            role = role,
            content = content,
            estimatedTokens = tokens,
            metadata = metadata
        )
        contextEntries.add(entry)
        totalEstimatedTokens += tokens

        if (shouldSummarize()) {
            compactContext()
        }
    }

    /**
     * Build the current context window for an inference request.
     */
    fun buildContextWindow(): ContextWindow {
        val budget = maxContextTokens - reservedResponseTokens
        val systemTokens = estimateTokens(systemPrompt)
        val summaryTokens = contextSummary?.let { estimateTokens(it) } ?: 0
        var remainingBudget = budget - systemTokens - summaryTokens

        // Include as many recent turns as fit within budget (most recent first)
        val recentTurns = mutableListOf<ContextEntry>()
        for (entry in contextEntries.reversed()) {
            if (entry.estimatedTokens <= remainingBudget) {
                recentTurns.add(0, entry)
                remainingBudget -= entry.estimatedTokens
            } else {
                break
            }
        }

        val totalUsed = systemTokens + summaryTokens + recentTurns.sumOf { it.estimatedTokens }
        return ContextWindow(
            systemPrompt = systemPrompt,
            summary = contextSummary,
            recentTurns = recentTurns,
            totalTokens = totalUsed,
            maxTokens = maxContextTokens,
            utilizationPercent = (totalUsed.toFloat() / budget) * 100f,
            turnCount = contextEntries.size,
            summarizedTurnCount = contextEntries.size - recentTurns.size
        )
    }

    /**
     * Format the context window as a prompt string for inference.
     */
    fun formatForInference(): String = buildString {
        append("[System]\n$systemPrompt\n\n")

        contextSummary?.let {
            append("[Context Summary]\n$it\n\n")
        }

        val window = buildContextWindow()
        for (entry in window.recentTurns) {
            append("[${entry.role}]\n${entry.content}\n\n")
        }
    }

    /**
     * Get statistics about the current context state.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_turns" to contextEntries.size,
        "estimated_tokens" to totalEstimatedTokens,
        "max_tokens" to maxContextTokens,
        "has_summary" to (contextSummary != null),
        "summarization_count" to summarizationCount,
        "utilization_percent" to buildContextWindow().utilizationPercent,
        "system_prompt_tokens" to estimateTokens(systemPrompt)
    )

    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }

    fun getSystemPrompt(): String = systemPrompt

    fun getTurnCount(): Int = contextEntries.size

    fun getContextSummary(): String? = contextSummary

    fun clear() {
        contextEntries.clear()
        contextSummary = null
        totalEstimatedTokens = 0
    }

    private fun shouldSummarize(): Boolean {
        val budget = maxContextTokens - reservedResponseTokens
        return totalEstimatedTokens > (budget * summaryTriggerRatio)
    }

    /**
     * Compact older context entries into a summary, keeping recent turns intact.
     * Uses extractive summarization: picks key sentences from older messages.
     */
    private fun compactContext() {
        if (contextEntries.size < MIN_TURNS_BEFORE_SUMMARY) return

        val keepCount = (contextEntries.size * RECENT_KEEP_RATIO).toInt().coerceAtLeast(MIN_KEEP_TURNS)
        val toSummarize = contextEntries.take(contextEntries.size - keepCount)

        if (toSummarize.isEmpty()) return

        val newSummary = extractiveSummarize(toSummarize)
        val previousSummary = contextSummary

        contextSummary = if (previousSummary != null) {
            "$previousSummary\n$newSummary"
        } else {
            newSummary
        }

        // Remove summarized entries
        val entriesToRemove = toSummarize.toList()
        contextEntries.removeAll(entriesToRemove.toSet())

        // Recalculate token count
        totalEstimatedTokens = contextEntries.sumOf { it.estimatedTokens } +
                (contextSummary?.let { estimateTokens(it) } ?: 0)
        summarizationCount++

        Log.d(TAG, "Context compacted: summarized ${toSummarize.size} turns, " +
                "keeping ${contextEntries.size} recent, tokens=$totalEstimatedTokens")
    }

    /**
     * Simple extractive summarizer: takes the first sentence of each turn
     * and combines user topics with AI key points.
     */
    private fun extractiveSummarize(entries: List<ContextEntry>): String = buildString {
        append("Previous conversation covered: ")
        val userTopics = mutableListOf<String>()
        val aiPoints = mutableListOf<String>()

        for (entry in entries) {
            val firstSentence = entry.content
                .split(Regex("[.!?]"))
                .firstOrNull()
                ?.trim()
                ?.take(MAX_SUMMARY_SENTENCE_LENGTH) ?: continue

            when (entry.role.lowercase()) {
                "you", "user" -> userTopics.add(firstSentence)
                else -> aiPoints.add(firstSentence)
            }
        }

        if (userTopics.isNotEmpty()) {
            append("User asked about: ")
            append(userTopics.joinToString("; "))
            append(". ")
        }
        if (aiPoints.isNotEmpty()) {
            append("AI discussed: ")
            append(aiPoints.take(MAX_AI_SUMMARY_POINTS).joinToString("; "))
            append(".")
        }
    }

    companion object {
        private const val TAG = "AIContextManager"
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 4096
        private const val DEFAULT_RESERVED_RESPONSE_TOKENS = 512
        private const val MIN_TURNS_BEFORE_SUMMARY = 6
        private const val RECENT_KEEP_RATIO = 0.4f
        private const val MIN_KEEP_TURNS = 3
        private const val MAX_SUMMARY_SENTENCE_LENGTH = 120
        private const val MAX_AI_SUMMARY_POINTS = 5

        private const val DEFAULT_SYSTEM_PROMPT = "You are Tron AI, a helpful assistant running " +
                "on the user's Android device. You have access to device sensors, " +
                "plugins, and on-device AI capabilities. Be concise and helpful."

        /**
         * Rough token estimation: ~4 characters per token for English text.
         */
        fun estimateTokens(text: String): Int {
            return (text.length / 4).coerceAtLeast(1)
        }
    }
}
