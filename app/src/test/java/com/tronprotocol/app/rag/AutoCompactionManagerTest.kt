package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class AutoCompactionManagerTest {

    private lateinit var manager: AutoCompactionManager
    private lateinit var ragStore: RAGStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        manager = AutoCompactionManager()
        ragStore = RAGStore(context, "test_compaction_ai")
    }

    @Test
    fun `initialization does not throw`() {
        assertNotNull(manager)
    }

    @Test
    fun `initialization with custom parameters does not throw`() {
        val customManager = AutoCompactionManager(
            maxContextTokens = 50_000,
            compactionThreshold = 0.8f,
            preserveRecentCount = 10
        )
        assertNotNull(customManager)
    }

    @Test
    fun `checkUsage returns token usage for empty store`() {
        val usage = manager.checkUsage(ragStore)
        assertNotNull(usage)
        assertEquals(0, usage.totalTokens)
        assertEquals(0, usage.chunkCount)
        assertEquals(AutoCompactionManager.DEFAULT_MAX_CONTEXT_TOKENS, usage.maxTokens)
        assertFalse(usage.needsCompaction)
    }

    @Test
    fun `checkUsage returns correct chunk count after adding knowledge`() {
        ragStore.addKnowledge("Some test knowledge about compaction", "test")
        ragStore.addKnowledge("Another piece of knowledge for testing", "test")

        val usage = manager.checkUsage(ragStore)
        assertTrue(usage.chunkCount >= 2)
    }

    @Test
    fun `shouldCompact equivalent via checkUsage returns false for empty store`() {
        val usage = manager.checkUsage(ragStore)
        assertFalse(usage.needsCompaction)
    }

    @Test
    fun `compactIfNeeded returns null when compaction not needed`() {
        val result = manager.compactIfNeeded(ragStore)
        assertNull(result)
    }

    @Test
    fun `compact on empty store returns successful result`() {
        val result = manager.compact(ragStore)
        assertNotNull(result)
        assertTrue(result.success)
        assertEquals(0, result.tokensRecovered)
        assertEquals(0, result.summariesCreated)
    }

    @Test
    fun `getStats returns statistics map`() {
        val stats = manager.getStats()
        assertNotNull(stats)
        assertTrue(stats.containsKey("total_compactions"))
        assertTrue(stats.containsKey("total_tokens_recovered"))
        assertTrue(stats.containsKey("last_compaction_time"))
        assertTrue(stats.containsKey("is_compacting"))
        assertTrue(stats.containsKey("max_context_tokens"))
        assertTrue(stats.containsKey("compaction_threshold"))
        assertTrue(stats.containsKey("preserve_recent"))
    }

    @Test
    fun `getStats initial values are zero`() {
        val stats = manager.getStats()
        assertEquals(0, stats["total_compactions"])
        assertEquals(0, stats["total_tokens_recovered"])
        assertEquals(0L, stats["last_compaction_time"])
        assertEquals(false, stats["is_compacting"])
    }

    @Test
    fun `compact increments total compactions count`() {
        manager.compact(ragStore)
        val stats = manager.getStats()
        assertEquals(1, stats["total_compactions"])
    }

    @Test
    fun `compaction result has valid compression ratio for empty store`() {
        val result = manager.compact(ragStore)
        // With 0 chunks before, compressionRatio is defined as 0
        assertEquals(0f, result.compressionRatio, 0.001f)
    }

    @Test
    fun `default constants have expected values`() {
        assertEquals(100_000, AutoCompactionManager.DEFAULT_MAX_CONTEXT_TOKENS)
        assertEquals(0.75f, AutoCompactionManager.DEFAULT_COMPACTION_THRESHOLD, 0.001f)
        assertEquals(20, AutoCompactionManager.DEFAULT_PRESERVE_RECENT)
    }
}
