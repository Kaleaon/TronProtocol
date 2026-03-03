package com.tronprotocol.app.rag

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalJsonlRetrievalMetricsSinkTest {

    @Test
    fun recordAndReadRecent_persistsQualityMetrics() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val aiId = "metrics_${System.nanoTime()}"
        val sink = LocalJsonlRetrievalMetricsSink(context, aiId)

        sink.record(
            RetrievalTelemetryEvent(
                timestampMs = 1L,
                aiId = aiId,
                strategy = "SEMANTIC",
                latencyMs = 7,
                resultCount = 3,
                topK = 3,
                topScore = 0.9f,
                avgScore = 0.7f,
                nDcgAtK = 0.88f,
                hitAtK = 1.0f,
                contradictionRate = 0.33f
            )
        )

        val saved = sink.readRecent(1).single()
        assertEquals(0.88f, saved.nDcgAtK)
        assertEquals(1.0f, saved.hitAtK)
        assertTrue(saved.contradictionRate > 0.3f)
    }
}
