package com.tronprotocol.app.rag

class DefaultResultReranker : ResultReranker {
    override fun rerank(
        query: String,
        strategy: RetrievalStrategy,
        candidates: List<RetrievalResult>,
        topK: Int
    ): List<RetrievalResult> {
        if (strategy != RetrievalStrategy.MEMRL) {
            return candidates.sortedByDescending { it.score }.take(topK)
        }

        return candidates
            .map { result ->
                val combinedScore = 0.7f * result.score + 0.3f * result.chunk.qValue
                result.copy(score = combinedScore)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }
}

class DefaultPostRetrievalValidator : PostRetrievalValidator {
    override fun validate(results: List<RetrievalResult>, topK: Int): List<RetrievalResult> {
        return results
            .filter { it.score.isFinite() }
            .distinctBy { it.chunk.chunkId }
            .sortedByDescending { it.score }
            .take(topK)
    }
}

object RetrievalQualityMetrics {
    fun nDcgAtK(results: List<RetrievalResult>, k: Int): Float {
        if (results.isEmpty() || k <= 0) return 0.0f
        val ranked = results.take(k)
        val dcg = ranked.mapIndexed { index, result ->
            val rel = relevanceLabel(result)
            ((1 shl rel) - 1).toFloat() / log2(index + 2f)
        }.sum()

        val ideal = ranked.map { relevanceLabel(it) }.sortedDescending()
        val idcg = ideal.mapIndexed { index, rel ->
            ((1 shl rel) - 1).toFloat() / log2(index + 2f)
        }.sum()

        if (idcg == 0.0f) return 0.0f
        return dcg / idcg
    }

    fun hitAtK(results: List<RetrievalResult>, k: Int): Float {
        if (results.isEmpty() || k <= 0) return 0.0f
        val hit = results.take(k).any { isHit(it) }
        return if (hit) 1.0f else 0.0f
    }

    fun contradictionRate(results: List<RetrievalResult>, k: Int): Float {
        if (results.isEmpty() || k <= 0) return 0.0f
        val top = results.take(k)
        val contradictions = top.count { isContradictory(it) }
        return contradictions.toFloat() / top.size
    }

    private fun relevanceLabel(result: RetrievalResult): Int {
        val fromMetadata = result.chunk.metadata["relevance_label"]?.toString()?.toIntOrNull()
        if (fromMetadata != null) {
            return fromMetadata.coerceIn(0, 3)
        }
        return when {
            result.score >= 0.8f -> 3
            result.score >= 0.6f -> 2
            result.score >= 0.4f -> 1
            else -> 0
        }
    }

    private fun isHit(result: RetrievalResult): Boolean {
        val flagged = result.chunk.metadata["is_hit"]?.toString()?.toBooleanStrictOrNull()
        return flagged ?: (result.score >= 0.5f)
    }

    private fun isContradictory(result: RetrievalResult): Boolean {
        val flagged = result.chunk.metadata["contradiction"]?.toString()?.toBooleanStrictOrNull()
        if (flagged != null) return flagged
        val content = result.chunk.content.lowercase()
        return listOf(" not ", " never ", " no ", " cannot ").any { marker ->
            content.contains(marker)
        }
    }

    private fun log2(value: Float): Float = (kotlin.math.ln(value.toDouble()) / kotlin.math.ln(2.0)).toFloat()
}
