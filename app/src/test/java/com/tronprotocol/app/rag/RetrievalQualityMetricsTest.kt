package com.tronprotocol.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalQualityMetricsTest {

    @Test
    fun computesExpectedQualityMetrics() {
        val c1 = TextChunk("1", "alpha", "s", "memory", "1", 1).apply {
            metadata["relevance_label"] = 3
            metadata["is_hit"] = true
        }
        val c2 = TextChunk("2", "beta not gamma", "s", "memory", "2", 1).apply {
            metadata["relevance_label"] = 1
            metadata["contradiction"] = true
        }
        val c3 = TextChunk("3", "delta", "s", "memory", "3", 1).apply {
            metadata["relevance_label"] = 0
        }

        val results = listOf(
            RetrievalResult(c1, 0.9f, RetrievalStrategy.SEMANTIC),
            RetrievalResult(c2, 0.6f, RetrievalStrategy.SEMANTIC),
            RetrievalResult(c3, 0.2f, RetrievalStrategy.SEMANTIC)
        )

        val ndcg = RetrievalQualityMetrics.nDcgAtK(results, 3)
        val hit = RetrievalQualityMetrics.hitAtK(results, 3)
        val contradictionRate = RetrievalQualityMetrics.contradictionRate(results, 3)

        assertEquals(1.0f, hit)
        assertTrue(ndcg > 0.9f)
        assertEquals(1f / 3f, contradictionRate)
    }
}
