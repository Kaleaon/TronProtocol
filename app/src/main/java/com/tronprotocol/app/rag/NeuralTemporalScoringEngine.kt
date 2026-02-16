package com.tronprotocol.app.rag

import kotlin.math.max

/**
 * Neural Temporal Stack scoring helper.
 *
 * Implements an approximation of the Memory Importance Scoring Engine (MISE)
 * from the TronProtocol infinite-context architecture docs.
 */
class NeuralTemporalScoringEngine {

    data class StageScores(
        val novelty: Float,
        val utility: Float,
        val emotionalSalience: Float,
        val recency: Float,
        val durability: Float,
        val aggregate: Float
    )

    fun assignStage(content: String, sourceType: String, baseImportance: Float? = null): MemoryStage {
        val normalizedSource = sourceType.lowercase()
        if (normalizedSource == "knowledge" || normalizedSource == "document") {
            return MemoryStage.SEMANTIC
        }

        val emotional = estimateEmotionalSalience(content)
        val novelty = estimateNovelty(content)
        val utility = baseImportance?.coerceIn(0.0f, 1.0f) ?: 0.5f
        val aggregate = (0.45f * utility) + (0.30f * emotional) + (0.25f * novelty)

        return when {
            aggregate >= 0.75f -> MemoryStage.EPISODIC
            aggregate >= 0.55f -> MemoryStage.WORKING
            else -> MemoryStage.SENSORY
        }
    }

    fun scoreForRetrieval(chunk: TextChunk, semanticSimilarity: Float, nowMs: Long): StageScores {
        val stage = MemoryStage.fromChunk(chunk)
        val recency = computeRecencyScore(chunk.timestamp, stage, nowMs)
        val utility = chunk.qValue.coerceIn(0.0f, 1.0f)
        val emotional = chunk.metadata["emotional_salience"]?.toString()?.toFloatOrNull()?.coerceIn(0.0f, 1.0f)
            ?: estimateEmotionalSalience(chunk.content)
        val novelty = chunk.metadata["novelty"]?.toString()?.toFloatOrNull()?.coerceIn(0.0f, 1.0f)
            ?: estimateNovelty(chunk.content)

        val aggregate =
            (0.40f * semanticSimilarity.coerceIn(0.0f, 1.0f)) +
                    (0.22f * utility) +
                    (0.15f * recency) +
                    (0.13f * stage.durabilityWeight) +
                    (0.10f * max(emotional, novelty))

        return StageScores(
            novelty = novelty,
            utility = utility,
            emotionalSalience = emotional,
            recency = recency,
            durability = stage.durabilityWeight,
            aggregate = aggregate.coerceIn(0.0f, 1.0f)
        )
    }

    fun estimateEmotionalSalience(content: String): Float {
        val lowered = content.lowercase()
        val hotWords = listOf(
            "urgent", "critical", "danger", "emergency", "important", "love", "fear",
            "warning", "alert", "pain", "help", "family", "security", "trust"
        )
        val matches = hotWords.count { lowered.contains(it) }
        return (matches / 5.0f).coerceIn(0.0f, 1.0f)
    }

    fun estimateNovelty(content: String): Float {
        val tokens = content
            .lowercase()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return 0.0f
        }
        val uniqueRatio = tokens.toSet().size.toFloat() / tokens.size.toFloat()
        return uniqueRatio.coerceIn(0.0f, 1.0f)
    }

    private fun computeRecencyScore(timestamp: String, stage: MemoryStage, nowMs: Long): Float {
        val created = timestamp.toLongOrNull() ?: return 0.5f
        val ageMs = (nowMs - created).coerceAtLeast(0L)
        val ttlMs = stage.defaultTtlMinutes * 60_000L
        if (ttlMs <= 0L) {
            return 0.0f
        }
        val normalizedAge = ageMs.toDouble() / ttlMs.toDouble()
        return (1.0 - normalizedAge).toFloat().coerceIn(0.0f, 1.0f)
    }
}
