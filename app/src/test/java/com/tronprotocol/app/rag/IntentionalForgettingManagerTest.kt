package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentionalForgettingManagerTest {

    private lateinit var context: Context
    private lateinit var forgettingManager: IntentionalForgettingManager
    private lateinit var ragStore: RAGStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val uniqueId = "test_ai_${System.nanoTime()}"
        ragStore = RAGStore(context, uniqueId, InMemorySink())
        forgettingManager = IntentionalForgettingManager(context, uniqueId)
    }

    @Test
    fun forget_returnsTrueForExistingChunk() {
        val chunkId = ragStore.addMemory("This is a memory to forget", 0.5f)
        val result = forgettingManager.forget(ragStore, chunkId, "no longer relevant")
        assertTrue("forget should return true for an existing chunk", result)
    }

    @Test
    fun forget_recordsReasonInLog() {
        val chunkId = ragStore.addMemory("Memory with specific content", 0.6f)
        val reason = "outdated information"
        forgettingManager.forget(ragStore, chunkId, reason)

        val log = forgettingManager.getForgettingLog()
        assertTrue("Forgetting log should not be empty after forgetting", log.isNotEmpty())

        val record = log.first()
        assertEquals("Recorded reason should match", reason, record.reason)
        assertEquals("Recorded chunkId should match", chunkId, record.chunkId)
    }

    @Test
    fun getForgettingLog_returnsRecordedOperations() {
        val id1 = ragStore.addMemory("First memory to forget", 0.3f)
        val id2 = ragStore.addMemory("Second memory to forget", 0.4f)

        forgettingManager.forget(ragStore, id1, "reason one")
        forgettingManager.forget(ragStore, id2, "reason two")

        val log = forgettingManager.getForgettingLog()
        assertEquals("Log should contain 2 records", 2, log.size)
    }

    private class InMemorySink : RetrievalTelemetrySink {
        val events = mutableListOf<RetrievalTelemetryEvent>()
        override fun record(metric: RetrievalTelemetryEvent) { events.add(metric) }
        override fun readRecent(limit: Int): List<RetrievalTelemetryEvent> = events.takeLast(limit)
    }
}
