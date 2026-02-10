package com.tronprotocol.app.aimodel

import android.content.Context
import android.util.Log
import com.tronprotocol.app.emotion.EmotionalStateManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Implicit Learning Manager
 *
 * Inspired by the subliminal-learning research (arXiv:2507.14805) which
 * demonstrated that behavioral traits transfer between AI models through
 * hidden statistical patterns in data — even when the data has been
 * filtered to remove explicit references to those traits.
 *
 * This manager implements constructive applications of implicit learning:
 *
 * 1. **Experience Distillation**: Extracts implicit patterns from interaction
 *    history to build behavioral preferences without explicit programming
 *
 * 2. **Auxiliary Signal Learning**: Monitors auxiliary signals from RAG
 *    retrieval patterns (which topics are queried most, which results
 *    are most helpful) to learn implicit user preferences
 *
 * 3. **Trait Emergence**: Personality traits emerge from accumulated
 *    experience rather than being hardcoded, creating more natural behavior
 *
 * 4. **Indirect Knowledge Transfer**: Knowledge learned in one domain
 *    can implicitly influence behavior in related domains through
 *    shared statistical patterns
 *
 * Key insight from the paper: "behavioral traits can transfer through
 * training data that appears semantically unrelated to those traits."
 * We apply this constructively by allowing the system to develop
 * preferences and behavioral patterns through regular interaction,
 * without needing explicit preference data.
 */
class ImplicitLearningManager(
    private val context: Context,
    private val emotionalManager: EmotionalStateManager
) {
    private val storage = SecureStorage(context)

    // Interaction pattern tracking
    private val topicFrequencies = mutableMapOf<String, Int>()
    private val successfulPatterns = mutableListOf<InteractionPattern>()
    private val domainAffinity = mutableMapOf<String, Float>()

    // Auxiliary signal accumulators
    private var totalInteractions: Int = 0
    private var positiveOutcomes: Int = 0
    private var negativeOutcomes: Int = 0

    init {
        loadState()
    }

    /**
     * Record an interaction for implicit learning.
     *
     * The system learns from the statistical patterns of interactions
     * rather than their explicit content — mirroring the subliminal
     * learning finding that traits transfer through distributional
     * properties of data.
     */
    fun recordInteraction(
        topic: String,
        domain: String,
        wasSuccessful: Boolean,
        retrievalStrategy: RetrievalStrategy?,
        responseQuality: Float
    ) {
        totalInteractions++

        // Track topic frequency (distributional signal)
        val normalizedTopic = topic.lowercase().trim()
        topicFrequencies[normalizedTopic] = (topicFrequencies[normalizedTopic] ?: 0) + 1

        // Track outcome patterns
        if (wasSuccessful) {
            positiveOutcomes++
            // Successful interactions reinforce domain affinity
            val currentAffinity = domainAffinity.getOrDefault(domain, 0.5f)
            domainAffinity[domain] = min(1.0f, currentAffinity + AFFINITY_LEARNING_RATE)

            // Record successful pattern for future reference
            if (successfulPatterns.size < MAX_PATTERNS) {
                successfulPatterns.add(
                    InteractionPattern(
                        domain = domain,
                        topic = normalizedTopic,
                        strategy = retrievalStrategy?.name ?: "NONE",
                        quality = responseQuality,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            // Reinforce emotional traits based on success
            emotionalManager.reinforceTrait("competence", 0.05f)
            if (domain.isNotEmpty()) {
                emotionalManager.reinforceTrait("expertise_$domain", 0.1f)
            }
        } else {
            negativeOutcomes++
            // Failed interactions weaken domain affinity
            val currentAffinity = domainAffinity.getOrDefault(domain, 0.5f)
            domainAffinity[domain] = (currentAffinity - AFFINITY_LEARNING_RATE * 0.5f).coerceAtLeast(0.0f)

            // Reinforce caution trait
            emotionalManager.reinforceTrait("caution", 0.1f)
        }

        // Periodically save state
        if (totalInteractions % SAVE_INTERVAL == 0) {
            saveState()
        }
    }

    /**
     * Get the implicitly learned preference for a retrieval strategy
     * based on historical success patterns in the given domain.
     *
     * This is an example of indirect knowledge transfer: success patterns
     * in one domain influence strategy selection in related domains.
     */
    fun getPreferredStrategy(domain: String): RetrievalStrategy {
        val domainPatterns = successfulPatterns.filter { it.domain == domain }

        if (domainPatterns.isEmpty()) {
            // No domain-specific data; use global patterns
            return getMostSuccessfulGlobalStrategy()
        }

        // Count strategy successes weighted by quality
        val strategyScores = mutableMapOf<String, Float>()
        for (pattern in domainPatterns) {
            val current = strategyScores.getOrDefault(pattern.strategy, 0.0f)
            strategyScores[pattern.strategy] = current + pattern.quality
        }

        val bestStrategy = strategyScores.maxByOrNull { it.value }?.key ?: "MEMRL"

        return try {
            RetrievalStrategy.valueOf(bestStrategy)
        } catch (e: IllegalArgumentException) {
            RetrievalStrategy.MEMRL
        }
    }

    /**
     * Get the system's implicit affinity for a domain.
     * Higher affinity means more successful interactions in that domain.
     */
    fun getDomainAffinity(domain: String): Float {
        return domainAffinity.getOrDefault(domain, 0.5f)
    }

    /**
     * Get topic frequency distribution.
     * Reveals what the system has been asked about most often.
     */
    fun getTopicDistribution(topN: Int = 20): List<Pair<String, Int>> {
        return topicFrequencies.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key to it.value }
    }

    /**
     * Perform experience distillation.
     *
     * Analyzes accumulated interaction patterns to extract implicit
     * behavioral tendencies. This is analogous to the subliminal-learning
     * paper's finding that fine-tuning on filtered data still transmits
     * traits through statistical patterns.
     *
     * Returns a DistillationReport with the extracted insights.
     */
    fun distillExperience(): DistillationReport {
        val report = DistillationReport()

        // Analyze topic clustering
        val topTopics = getTopicDistribution(10)
        report.dominantTopics = topTopics.map { it.first }

        // Analyze domain strengths
        val strongDomains = domainAffinity.entries
            .filter { it.value > 0.7f }
            .sortedByDescending { it.value }
            .map { it.key }
        report.strongDomains = strongDomains

        val weakDomains = domainAffinity.entries
            .filter { it.value < 0.3f }
            .sortedBy { it.value }
            .map { it.key }
        report.weakDomains = weakDomains

        // Compute success rate
        report.overallSuccessRate = if (totalInteractions > 0) {
            positiveOutcomes.toFloat() / totalInteractions
        } else 0.0f

        // Analyze strategy effectiveness
        val strategyEffectiveness = mutableMapOf<String, Float>()
        for (strategy in RetrievalStrategy.values()) {
            val patterns = successfulPatterns.filter { it.strategy == strategy.name }
            if (patterns.isNotEmpty()) {
                strategyEffectiveness[strategy.name] = patterns.map { it.quality }.average().toFloat()
            }
        }
        report.strategyEffectiveness = strategyEffectiveness

        // Generate behavioral insights
        report.insights = generateInsights(report)

        report.totalInteractions = totalInteractions
        report.timestamp = System.currentTimeMillis()

        Log.d(TAG, "Distilled experience: ${report.insights.size} insights from $totalInteractions interactions")
        return report
    }

    /**
     * Transfer learned knowledge to a RAG store.
     * Stores distilled experience as knowledge chunks so the RAG system
     * can use implicit learning results in future retrievals.
     */
    fun transferToRAG(ragStore: RAGStore) {
        val report = distillExperience()

        // Store domain expertise as knowledge
        for (domain in report.strongDomains) {
            val affinity = domainAffinity[domain] ?: continue
            ragStore.addKnowledge(
                "System has strong expertise in $domain (affinity: ${String.format("%.2f", affinity)}). " +
                        "Interactions in this domain have been consistently successful.",
                "implicit_learning"
            )
        }

        // Store strategy preferences as knowledge
        for ((strategy, effectiveness) in report.strategyEffectiveness) {
            if (effectiveness > 0.7f) {
                ragStore.addKnowledge(
                    "Retrieval strategy $strategy has been highly effective " +
                            "(quality: ${String.format("%.2f", effectiveness)}).",
                    "implicit_learning"
                )
            }
        }

        // Store behavioral insights
        for (insight in report.insights) {
            ragStore.addKnowledge(insight, "implicit_learning")
        }

        Log.d(TAG, "Transferred implicit knowledge to RAG store")
    }

    /**
     * Get comprehensive learning statistics.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "total_interactions" to totalInteractions,
            "positive_outcomes" to positiveOutcomes,
            "negative_outcomes" to negativeOutcomes,
            "success_rate" to if (totalInteractions > 0) {
                positiveOutcomes.toFloat() / totalInteractions
            } else 0.0f,
            "tracked_topics" to topicFrequencies.size,
            "tracked_domains" to domainAffinity.size,
            "pattern_count" to successfulPatterns.size,
            "strong_domains" to domainAffinity.filter { it.value > 0.7f }.keys.toList(),
            "weak_domains" to domainAffinity.filter { it.value < 0.3f }.keys.toList()
        )
    }

    // Internal helpers

    private fun getMostSuccessfulGlobalStrategy(): RetrievalStrategy {
        if (successfulPatterns.isEmpty()) return RetrievalStrategy.MEMRL

        val strategyCounts = successfulPatterns.groupBy { it.strategy }
            .mapValues { it.value.map { p -> p.quality }.average() }

        val best = strategyCounts.maxByOrNull { it.value }?.key ?: "MEMRL"

        return try {
            RetrievalStrategy.valueOf(best)
        } catch (e: IllegalArgumentException) {
            RetrievalStrategy.MEMRL
        }
    }

    private fun generateInsights(report: DistillationReport): List<String> {
        val insights = mutableListOf<String>()

        if (report.overallSuccessRate > 0.8f) {
            insights.add("Overall interaction success rate is high (${String.format("%.0f", report.overallSuccessRate * 100)}%), " +
                    "indicating reliable performance.")
        } else if (report.overallSuccessRate < 0.5f && totalInteractions > 10) {
            insights.add("Success rate is below 50%. Consider increasing RAG retrieval depth " +
                    "or adding more knowledge to weak domains.")
        }

        if (report.strongDomains.isNotEmpty()) {
            insights.add("Strongest domains: ${report.strongDomains.joinToString(", ")}. " +
                    "These areas show consistently successful interactions.")
        }

        if (report.weakDomains.isNotEmpty()) {
            insights.add("Domains needing improvement: ${report.weakDomains.joinToString(", ")}. " +
                    "Consider adding more knowledge or adjusting retrieval strategies.")
        }

        val bestStrategy = report.strategyEffectiveness.maxByOrNull { it.value }
        if (bestStrategy != null && bestStrategy.value > 0.7f) {
            insights.add("Most effective retrieval strategy: ${bestStrategy.key} " +
                    "(avg quality: ${String.format("%.2f", bestStrategy.value)}).")
        }

        return insights
    }

    // Persistence

    private fun saveState() {
        try {
            val stateObj = JSONObject().apply {
                put("totalInteractions", totalInteractions)
                put("positiveOutcomes", positiveOutcomes)
                put("negativeOutcomes", negativeOutcomes)

                val topicsObj = JSONObject()
                for ((topic, count) in topicFrequencies) {
                    topicsObj.put(topic, count)
                }
                put("topicFrequencies", topicsObj)

                val affinityObj = JSONObject()
                for ((domain, affinity) in domainAffinity) {
                    affinityObj.put(domain, affinity.toDouble())
                }
                put("domainAffinity", affinityObj)

                val patternsArray = JSONArray()
                for (pattern in successfulPatterns.takeLast(MAX_PATTERNS)) {
                    val patternObj = JSONObject().apply {
                        put("domain", pattern.domain)
                        put("topic", pattern.topic)
                        put("strategy", pattern.strategy)
                        put("quality", pattern.quality.toDouble())
                        put("timestamp", pattern.timestamp)
                    }
                    patternsArray.put(patternObj)
                }
                put("patterns", patternsArray)
            }

            storage.store(STATE_KEY, stateObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving implicit learning state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(STATE_KEY) ?: return
            val stateObj = JSONObject(data)

            totalInteractions = stateObj.optInt("totalInteractions", 0)
            positiveOutcomes = stateObj.optInt("positiveOutcomes", 0)
            negativeOutcomes = stateObj.optInt("negativeOutcomes", 0)

            val topicsObj = stateObj.optJSONObject("topicFrequencies")
            if (topicsObj != null) {
                val keys = topicsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    topicFrequencies[key] = topicsObj.getInt(key)
                }
            }

            val affinityObj = stateObj.optJSONObject("domainAffinity")
            if (affinityObj != null) {
                val keys = affinityObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    domainAffinity[key] = affinityObj.getDouble(key).toFloat()
                }
            }

            val patternsArray = stateObj.optJSONArray("patterns")
            if (patternsArray != null) {
                for (i in 0 until patternsArray.length()) {
                    val obj = patternsArray.getJSONObject(i)
                    successfulPatterns.add(
                        InteractionPattern(
                            domain = obj.getString("domain"),
                            topic = obj.getString("topic"),
                            strategy = obj.getString("strategy"),
                            quality = obj.getDouble("quality").toFloat(),
                            timestamp = obj.getLong("timestamp")
                        )
                    )
                }
            }

            Log.d(TAG, "Loaded implicit learning state: $totalInteractions interactions")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading implicit learning state", e)
        }
    }

    /**
     * An interaction pattern recorded for implicit learning.
     */
    data class InteractionPattern(
        val domain: String,
        val topic: String,
        val strategy: String,
        val quality: Float,
        val timestamp: Long
    )

    /**
     * Report from experience distillation.
     */
    class DistillationReport {
        var dominantTopics: List<String> = emptyList()
        var strongDomains: List<String> = emptyList()
        var weakDomains: List<String> = emptyList()
        var overallSuccessRate: Float = 0.0f
        var strategyEffectiveness: Map<String, Float> = emptyMap()
        var insights: List<String> = emptyList()
        var totalInteractions: Int = 0
        var timestamp: Long = 0L

        override fun toString(): String {
            return "DistillationReport{" +
                    "interactions=$totalInteractions, " +
                    "successRate=${String.format("%.2f", overallSuccessRate)}, " +
                    "strongDomains=$strongDomains, " +
                    "insights=${insights.size}" +
                    "}"
        }
    }

    companion object {
        private const val TAG = "ImplicitLearning"
        private const val STATE_KEY = "implicit_learning_state"
        private const val AFFINITY_LEARNING_RATE = 0.05f
        private const val MAX_PATTERNS = 200
        private const val SAVE_INTERVAL = 10
    }
}
