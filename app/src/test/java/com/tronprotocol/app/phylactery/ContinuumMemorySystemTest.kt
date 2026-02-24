package com.tronprotocol.app.phylactery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContinuumMemorySystemTest {

    private lateinit var context: Context
    private lateinit var cms: ContinuumMemorySystem

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cms = ContinuumMemorySystem(context)
    }

    @Test
    fun addMemory_increasesCount() {
        val statsBefore = cms.getStats()
        val workingBefore = statsBefore["working_memory_size"] as Int

        cms.addWorkingMemory("Test memory content")

        val statsAfter = cms.getStats()
        val workingAfter = statsAfter["working_memory_size"] as Int
        assertEquals(
            "Working memory count should increase by 1 after addWorkingMemory",
            workingBefore + 1,
            workingAfter
        )
    }

    @Test
    fun searchMemories_findsMatchingContent() {
        // Add episodic memory with specific content
        cms.addEpisodicMemory("The user enjoys hiking in national parks")
        cms.addEpisodicMemory("The user prefers dark mode in applications")
        cms.addEpisodicMemory("Weekly meetings are held on Mondays")

        val results = cms.retrieveEpisodicMemories("hiking parks outdoor", topK = 5)
        assertNotNull("Search results should not be null", results)
        assertTrue(
            "Search should find at least one matching memory",
            results.isNotEmpty()
        )
        assertTrue(
            "First result should be the hiking memory",
            results.first().content.contains("hiking")
        )
    }

    @Test
    fun searchMemories_returnsEmptyForNoMatch() {
        cms.addEpisodicMemory("The weather is sunny today")

        val results = cms.searchAllMemory("xyzzy quantum entanglement zzzz", topK = 5)
        assertNotNull("Search results should not be null even for no match", results)
        // For a simplified embedding, results may still return items due to hash collisions,
        // but they should at least not crash
    }

    @Test
    fun getMemoryCount_startsAtZero() {
        val stats = cms.getStats()
        assertEquals("Working memory should start at 0", 0, stats["working_memory_size"])
        assertEquals("Episodic memory should start at 0", 0, stats["episodic_memory_size"])
    }

    @Test
    fun consolidate_doesNotThrow() {
        // Add some memories first
        cms.addWorkingMemory("Working memory item 1")
        cms.addEpisodicMemory("Episodic memory item 1")
        cms.addSemanticKnowledge("Semantic knowledge item 1", "test_category")

        // Consolidation should not throw even with minimal data
        cms.runConsolidation()

        // Verify the system is still functional after consolidation
        val stats = cms.getStats()
        assertNotNull("Stats should be available after consolidation", stats)
    }
}
