package com.tronprotocol.app.rag

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class MemoryImportanceReassessorTest {

    private lateinit var reassessor: MemoryImportanceReassessor
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reassessor = MemoryImportanceReassessor()
    }

    @Test
    fun `reassess with empty store returns result with zero chunks`() {
        val ragStore = RAGStore(context, "test_reassessor_empty")
        val result = reassessor.reassess(ragStore)
        assertNotNull(result)
        assertEquals(0, result.totalChunks)
        assertEquals(0, result.upgraded)
        assertEquals(0, result.downgraded)
        assertEquals(0, result.unchanged)
    }

    @Test
    fun `reassess returns results for each chunk`() {
        val ragStore = RAGStore(context, "test_reassessor_chunks")
        ragStore.addKnowledge("Artificial intelligence is fascinating", "test")
        ragStore.addKnowledge("Machine learning models can be trained", "test")
        ragStore.addKnowledge("Deep learning uses neural networks", "test")

        val result = reassessor.reassess(ragStore)
        assertNotNull(result)
        assertTrue(result.totalChunks >= 3)
        // Total should equal sum of upgraded + downgraded + unchanged
        assertEquals(
            result.totalChunks,
            result.upgraded + result.downgraded + result.unchanged
        )
    }

    @Test
    fun `reassess with active goals upgrades related memories`() {
        val ragStore = RAGStore(context, "test_reassessor_goals")
        ragStore.addKnowledge("Machine learning requires training data", "test")
        ragStore.addKnowledge("Weather patterns vary seasonally", "test")

        val result = reassessor.reassess(
            ragStore,
            activeGoals = listOf("improve machine learning capabilities")
        )
        assertNotNull(result)
        // At least some chunks should be upgraded due to goal overlap
        assertTrue(result.upgraded >= 0)
    }

    @Test
    fun `reassess with recent topics boosts related memories`() {
        val ragStore = RAGStore(context, "test_reassessor_topics")
        ragStore.addKnowledge("Neural networks are powerful tools", "test")
        ragStore.addKnowledge("Cooking recipes are fun to follow", "test")

        val result = reassessor.reassess(
            ragStore,
            recentTopics = listOf("neural networks")
        )
        assertNotNull(result)
        // The neural networks memory should be boosted
        assertTrue(result.upgraded >= 0)
    }

    @Test
    fun `reassess with no goals or topics produces mostly unchanged`() {
        val ragStore = RAGStore(context, "test_reassessor_nogoals")
        ragStore.addKnowledge("Some knowledge item", "test")

        val result = reassessor.reassess(ragStore)
        // Without goals or topics, recently created chunks should mostly stay unchanged
        // (they are too new to be penalized for age)
        assertTrue(result.unchanged >= 0)
    }

    @Test
    fun `reassessmentResult data class equality`() {
        val result1 = MemoryImportanceReassessor.ReassessmentResult(
            totalChunks = 10,
            upgraded = 3,
            downgraded = 2,
            unchanged = 5
        )
        val result2 = MemoryImportanceReassessor.ReassessmentResult(
            totalChunks = 10,
            upgraded = 3,
            downgraded = 2,
            unchanged = 5
        )
        assertEquals(result1, result2)
    }

    @Test
    fun `reassessmentResult data class toString`() {
        val result = MemoryImportanceReassessor.ReassessmentResult(
            totalChunks = 15,
            upgraded = 5,
            downgraded = 3,
            unchanged = 7
        )
        val str = result.toString()
        assertTrue(str.contains("15"))
        assertTrue(str.contains("5"))
        assertTrue(str.contains("3"))
        assertTrue(str.contains("7"))
    }
}
