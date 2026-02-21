package com.tronprotocol.app.rag

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RAGStoreTelemetryTest {

    @Test
    fun retrieve_recordsTelemetryWithoutBreakingResults() {
        val sink = InMemoryTelemetrySink()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = RAGStore(context, "telemetry_ok_${System.nanoTime()}", sink)

        store.addMemory("Kotlin coroutines improve async programming", 0.8f)
        val results = store.retrieve("kotlin async", RetrievalStrategy.SEMANTIC, 5)

        assertFalse(results.isEmpty())
        assertEquals(1, sink.events.size)
        assertEquals("SEMANTIC", sink.events.first().strategy)
        assertTrue(sink.events.first().latencyMs >= 0)
    }

    @Test
    fun retrieve_survivesTelemetrySinkFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = RAGStore(context, "telemetry_fail_${System.nanoTime()}", FailingTelemetrySink())

        store.addKnowledge("The capital of France is Paris", "geography")
        val results = store.retrieve("capital france", RetrievalStrategy.KEYWORD, 3)

        assertFalse(results.isEmpty())
        assertNotNull(results.first().scoreDistribution)
        assertEquals("KEYWORD", results.first().strategyId)
    }

    private class InMemoryTelemetrySink : RetrievalTelemetrySink {
        val events = mutableListOf<RetrievalTelemetryEvent>()

        override fun record(metric: RetrievalTelemetryEvent) {
            events.add(metric)
        }

        override fun readRecent(limit: Int): List<RetrievalTelemetryEvent> =
            events.takeLast(limit)
    }

    private class FailingTelemetrySink : RetrievalTelemetrySink {
        override fun record(metric: RetrievalTelemetryEvent) {
            error("synthetic telemetry sink failure")
        }

        override fun readRecent(limit: Int): List<RetrievalTelemetryEvent> = emptyList()
    }
}
