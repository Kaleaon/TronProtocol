package com.tronprotocol.app.rag

import android.util.Log

/**
 * Periodic re-evaluation of memory importance based on current goals and context,
 * not just retrieval frequency. A memory retrieved zero times may still be critical
 * if it relates to a dormant but important goal.
 */
class MemoryImportanceReassessor {

    fun reassess(
        store: RAGStore,
        activeGoals: List<String> = emptyList(),
        recentTopics: List<String> = emptyList()
    ): ReassessmentResult {
        val chunks = store.getChunks()
        var upgraded = 0
        var downgraded = 0
        var unchanged = 0

        for (chunk in chunks) {
            val originalQ = chunk.qValue
            var adjustment = 0f

            // Boost memories related to active goals
            for (goal in activeGoals) {
                val goalTokens = goal.lowercase().split("\\s+".toRegex())
                val content = chunk.content.lowercase()
                val overlap = goalTokens.count { content.contains(it) }
                if (overlap > 0) {
                    adjustment += 0.05f * overlap.coerceAtMost(3)
                }
            }

            // Boost memories related to recent topics
            for (topic in recentTopics) {
                if (chunk.content.lowercase().contains(topic.lowercase())) {
                    adjustment += 0.03f
                }
            }

            // Penalize very old memories with no retrievals
            val ageMs = System.currentTimeMillis() - chunk.timestamp.toLongOrNull().let { it ?: System.currentTimeMillis() }
            val ageDays = ageMs / (24 * 60 * 60 * 1000f)
            if (chunk.retrievalCount == 0 && ageDays > 30 && adjustment == 0f) {
                adjustment -= 0.02f
            }

            // Boost memories with high emotional salience
            val emotionalSalience = (chunk.metadata["emotional_salience"] as? Number)?.toFloat() ?: 0f
            if (emotionalSalience > 0.7f) {
                adjustment += 0.04f
            }

            // Apply adjustment
            if (adjustment != 0f) {
                val newQ = (originalQ + adjustment).coerceIn(0f, 1f)
                chunk.restoreMemRLState(newQ, chunk.retrievalCount, chunk.successCount)
                if (adjustment > 0) upgraded++ else downgraded++
            } else {
                unchanged++
            }
        }

        val result = ReassessmentResult(
            totalChunks = chunks.size,
            upgraded = upgraded,
            downgraded = downgraded,
            unchanged = unchanged
        )
        Log.d(TAG, "Reassessment complete: $result")
        return result
    }

    data class ReassessmentResult(
        val totalChunks: Int,
        val upgraded: Int,
        val downgraded: Int,
        val unchanged: Int
    )

    companion object {
        private const val TAG = "MemoryReassessor"
    }
}
