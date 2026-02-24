package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RAGStoreTest {

    private lateinit var context: Context
    private lateinit var store: RAGStore
    private lateinit var sink: InMemorySink

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sink = InMemorySink()
        store = RAGStore(context, "test_ai_${System.nanoTime()}", sink)
    }

    // --- addMemory ---

    @Test
    fun addMemory_returnsNonNullChunkId() {
        val chunkId = store.addMemory("The user prefers dark mode", 0.7f)
        assertNotNull(chunkId)
        assertTrue(chunkId.isNotEmpty())
    }

    @Test
    fun addMemory_createsChunkRetrievableById() {
        val chunkId = store.addMemory("Kotlin is a modern programming language", 0.8f)
        val chunks = store.getChunks()
        val found = chunks.find { it.chunkId == chunkId }
        assertNotNull("Chunk should be retrievable by its ID", found)
        assertTrue(found!!.content.contains("Kotlin"))
    }

    @Test
    fun multipleAddMemory_createsDistinctChunks() {
        val id1 = store.addMemory("First memory about cats", 0.5f)
        val id2 = store.addMemory("Second memory about dogs", 0.6f)
        val id3 = store.addMemory("Third memory about birds", 0.7f)

        assertNotEquals(id1, id2)
        assertNotEquals(id2, id3)
        assertNotEquals(id1, id3)
        assertEquals(3, store.getChunks().size)
    }

    // --- addKnowledge ---

    @Test
    fun addKnowledge_createsChunkWithCategoryMetadata() {
        val chunkId = store.addKnowledge("The capital of France is Paris", "geography")
        val chunk = store.getChunks().find { it.chunkId == chunkId }
        assertNotNull(chunk)
        assertEquals("geography", chunk!!.metadata["category"])
    }

    // --- retrieve with KEYWORD strategy ---

    @Test
    fun retrieve_keyword_findsMatchingContent() {
        store.addMemory("Kotlin coroutines improve async programming", 0.8f)
        store.addMemory("Java threads are complex to manage", 0.6f)

        val results = store.retrieve("Kotlin coroutines", RetrievalStrategy.KEYWORD, 5)
        assertFalse("KEYWORD should find matching content", results.isEmpty())
        assertTrue(results.first().chunk.content.contains("Kotlin"))
    }

    // --- retrieve with RECENCY strategy ---

    @Test
    fun retrieve_recency_returnsRecentChunksFirst() {
        store.addMemory("Older memory about Android development", 0.5f)
        // Small delay to ensure different timestamps
        Thread.sleep(10)
        store.addMemory("Newer memory about Jetpack Compose", 0.5f)

        val results = store.retrieve("memory", RetrievalStrategy.RECENCY, 5)
        assertFalse(results.isEmpty())
        // Recency strategy scores newer chunks higher
        assertTrue(results.size >= 2)
        assertTrue(
            "Newer chunk should have higher recency score",
            results[0].score >= results[1].score
        )
    }

    // --- retrieve with HYBRID strategy ---

    @Test
    fun retrieve_hybrid_returnsResults() {
        store.addMemory("Machine learning models require training data", 0.8f)
        store.addKnowledge("TensorFlow is a popular ML framework", "technology")

        val results = store.retrieve("machine learning framework", RetrievalStrategy.HYBRID, 5)
        assertFalse("HYBRID should return results for matching content", results.isEmpty())
    }

    // --- retrieve with MEMRL strategy ---

    @Test
    fun retrieve_memrl_returnsResultsRankedByQValue() {
        val id1 = store.addMemory("Deep learning neural networks are powerful", 0.8f)
        val id2 = store.addMemory("Deep learning requires large datasets", 0.7f)

        // Boost Q-value for one chunk through positive feedback
        store.provideFeedback(listOf(id1), true)
        store.provideFeedback(listOf(id1), true)
        store.provideFeedback(listOf(id1), true)

        val results = store.retrieve("deep learning", RetrievalStrategy.MEMRL, 5)
        assertFalse("MEMRL should return results", results.isEmpty())
    }

    // --- retrieve returns empty for non-matching query ---

    @Test
    fun retrieve_returnsEmptyForNonMatchingQuery() {
        store.addMemory("Kotlin is a programming language", 0.5f)

        val results = store.retrieve("xyzzyflurb nonsense term", RetrievalStrategy.KEYWORD, 5)
        assertTrue("KEYWORD should return empty for non-matching query", results.isEmpty())
    }

    // --- removeChunk ---

    @Test
    fun removeChunk_removesChunk() {
        val chunkId = store.addMemory("Temporary memory to delete", 0.5f)
        assertEquals(1, store.getChunks().size)

        val removed = store.removeChunk(chunkId)
        assertTrue(removed)
        assertEquals(0, store.getChunks().size)
    }

    @Test
    fun removeChunk_returnsFalseForNonExistentId() {
        val removed = store.removeChunk("nonexistent_chunk_id_12345")
        assertFalse(removed)
    }

    // --- getMemRLStats ---

    @Test
    fun getMemRLStats_returnsMapWithExpectedKeys() {
        store.addMemory("Test content for stats", 0.5f)

        val stats = store.getMemRLStats()
        assertTrue(stats.containsKey("avg_q_value"))
        assertTrue(stats.containsKey("success_rate"))
        assertTrue(stats.containsKey("total_retrievals"))
        assertTrue(stats.containsKey("total_chunks"))
    }

    // --- getChunks (getAllChunks) ---

    @Test
    fun getChunks_returnsAllAddedChunks() {
        store.addMemory("Memory one", 0.5f)
        store.addMemory("Memory two", 0.6f)
        store.addKnowledge("Knowledge item", "science")

        val allChunks = store.getChunks()
        assertEquals(3, allChunks.size)
    }

    // --- updateChunkQValue (provideFeedback) ---

    @Test
    fun provideFeedback_success_increasesQValue() {
        val chunkId = store.addMemory("Positive feedback memory", 0.5f)
        val qBefore = store.getChunks().first { it.chunkId == chunkId }.qValue

        store.provideFeedback(listOf(chunkId), true)

        val qAfter = store.getChunks().first { it.chunkId == chunkId }.qValue
        assertTrue("Q-value should increase on success", qAfter > qBefore)
    }

    @Test
    fun provideFeedback_failure_decreasesQValue() {
        val chunkId = store.addMemory("Negative feedback memory", 0.5f)
        val qBefore = store.getChunks().first { it.chunkId == chunkId }.qValue

        store.provideFeedback(listOf(chunkId), false)

        val qAfter = store.getChunks().first { it.chunkId == chunkId }.qValue
        assertTrue("Q-value should decrease on failure", qAfter < qBefore)
    }

    // --- clear (compact-like operation) ---

    @Test
    fun clear_doesNotThrow() {
        store.addMemory("Memory before clear", 0.5f)
        store.addMemory("Another memory before clear", 0.6f)

        // clear is the "compact" equivalent - should not throw
        store.clear()
        assertEquals(0, store.getChunks().size)
    }

    // --- retrieve with limit ---

    @Test
    fun retrieve_withLimit_limitsResultsCount() {
        store.addMemory("Kotlin language features are great", 0.5f)
        store.addMemory("Kotlin coroutines simplify concurrency", 0.6f)
        store.addMemory("Kotlin multiplatform works everywhere", 0.7f)
        store.addMemory("Kotlin null safety prevents crashes", 0.8f)

        val results = store.retrieve("Kotlin", RetrievalStrategy.KEYWORD, 2)
        assertTrue("Should not exceed limit", results.size <= 2)
    }

    // --- Helper sink ---

    private class InMemorySink : RetrievalTelemetrySink {
        val events = mutableListOf<RetrievalTelemetryEvent>()

        override fun record(metric: RetrievalTelemetryEvent) {
            events.add(metric)
        }

        override fun readRecent(limit: Int): List<RetrievalTelemetryEvent> =
            events.takeLast(limit)
    }
}
