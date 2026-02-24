package com.tronprotocol.app.rag

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryConsolidationManagerTest {

    private lateinit var manager: MemoryConsolidationManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = MemoryConsolidationManager(context)
    }

    @Test
    fun `initialization does not throw`() {
        assertNotNull(manager)
    }

    @Test
    fun `getStats returns map with expected keys`() {
        val stats = manager.getStats()
        assertNotNull(stats)
        assertTrue(stats.containsKey("total_consolidations"))
        assertTrue(stats.containsKey("memories_strengthened"))
        assertTrue(stats.containsKey("memories_weakened"))
        assertTrue(stats.containsKey("memories_forgotten"))
    }

    @Test
    fun `getStats initial values are zero`() {
        val stats = manager.getStats()
        assertEquals(0, stats["total_consolidations"])
        assertEquals(0, stats["memories_strengthened"])
        assertEquals(0, stats["memories_weakened"])
        assertEquals(0, stats["memories_forgotten"])
    }

    @Test
    fun `getStats does not contain avg keys when no consolidations`() {
        val stats = manager.getStats()
        // avg keys are only added when totalConsolidations > 0
        assertFalse(stats.containsKey("avg_strengthened_per_consolidation"))
        assertFalse(stats.containsKey("avg_forgotten_per_consolidation"))
    }

    @Test
    fun `consolidate returns result with success flag`() {
        val ragStore = RAGStore(context, "test_consolidation_ai")
        ragStore.addKnowledge("Test knowledge for consolidation", "test")

        val result = manager.consolidate(ragStore)
        assertNotNull(result)
        assertTrue(result.success)
        assertTrue(result.duration >= 0)
    }

    @Test
    fun `consolidate updates stats`() {
        val ragStore = RAGStore(context, "test_consolidation_ai2")
        ragStore.addKnowledge("Some knowledge to consolidate", "test")

        manager.consolidate(ragStore)
        val stats = manager.getStats()
        assertEquals(1, stats["total_consolidations"])
    }

    @Test
    fun `consolidation result fields are populated`() {
        val ragStore = RAGStore(context, "test_consolidation_ai3")
        ragStore.addKnowledge("Memory about artificial intelligence", "test")
        ragStore.addKnowledge("Memory about machine learning", "test")

        val result = manager.consolidate(ragStore)
        assertTrue(result.success)
        // These fields should be non-negative
        assertTrue(result.strengthened >= 0)
        assertTrue(result.weakened >= 0)
        assertTrue(result.forgotten >= 0)
        assertTrue(result.connections >= 0)
        assertTrue(result.optimized >= 0)
    }

    @Test
    fun `optimizer can be set`() {
        val optimizer = SleepCycleOptimizer(context)
        manager.optimizer = optimizer
        assertNotNull(manager.optimizer)
    }

    @Test
    fun `isConsolidationTime returns false when device is active`() {
        // In a test environment, the device is always "active" (interactive)
        val result = manager.isConsolidationTime()
        assertFalse(result)
    }

    @Test
    fun `consolidation result toString contains key info`() {
        val ragStore = RAGStore(context, "test_consolidation_toString")
        val result = manager.consolidate(ragStore)
        val str = result.toString()
        assertTrue(str.contains("success="))
        assertTrue(str.contains("strengthened="))
        assertTrue(str.contains("weakened="))
        assertTrue(str.contains("forgotten="))
        assertTrue(str.contains("duration="))
    }
}
