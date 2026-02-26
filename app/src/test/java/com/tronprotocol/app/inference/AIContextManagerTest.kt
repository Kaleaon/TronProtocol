package com.tronprotocol.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AIContextManagerTest {

    private lateinit var manager: AIContextManager

    @Before
    fun setUp() {
        manager = AIContextManager(
            maxContextTokens = 4096,
            reservedResponseTokens = 512
        )
    }

    @Test
    fun testAddTurn() {
        manager.addTurn("You", "Hello, how are you?")
        assertEquals(1, manager.getTurnCount())

        manager.addTurn("Tron AI", "I'm doing well, thanks for asking!")
        assertEquals(2, manager.getTurnCount())
    }

    @Test
    fun testBuildContextWindow() {
        manager.addTurn("You", "What is the weather?")
        manager.addTurn("Tron AI", "I don't have access to weather data currently.")
        manager.addTurn("You", "Can you help with math?")

        val window = manager.buildContextWindow()
        assertEquals(3, window.turnCount)
        assertEquals(3, window.recentTurns.size)
        assertTrue(window.totalTokens > 0)
        assertTrue(window.utilizationPercent > 0f)
    }

    @Test
    fun testFormatForInference() {
        manager.addTurn("You", "Hello")
        manager.addTurn("Tron AI", "Hi there!")

        val formatted = manager.formatForInference()
        assertTrue(formatted.contains("[System]"))
        assertTrue(formatted.contains("[You]"))
        assertTrue(formatted.contains("Hello"))
        assertTrue(formatted.contains("[Tron AI]"))
        assertTrue(formatted.contains("Hi there!"))
    }

    @Test
    fun testClear() {
        manager.addTurn("You", "Test message")
        assertEquals(1, manager.getTurnCount())

        manager.clear()
        assertEquals(0, manager.getTurnCount())
        assertNull(manager.getContextSummary())
    }

    @Test
    fun testSetSystemPrompt() {
        val customPrompt = "You are a math tutor."
        manager.setSystemPrompt(customPrompt)
        assertEquals(customPrompt, manager.getSystemPrompt())
    }

    @Test
    fun testEstimateTokens() {
        val tokens = AIContextManager.estimateTokens("Hello world this is a test")
        // ~26 chars / 4 = ~6 tokens
        assertTrue(tokens > 0)
        assertTrue(tokens < 20)
    }

    @Test
    fun testEstimateTokensEmpty() {
        val tokens = AIContextManager.estimateTokens("")
        assertEquals(1, tokens) // Minimum 1 token
    }

    @Test
    fun testGetStats() {
        manager.addTurn("You", "First message")
        manager.addTurn("Tron AI", "First response")

        val stats = manager.getStats()
        assertEquals(2, stats["total_turns"])
        assertTrue((stats["estimated_tokens"] as Int) > 0)
        assertEquals(4096, stats["max_tokens"])
        assertEquals(false, stats["has_summary"])
    }

    @Test
    fun testContextWindowUtilization() {
        manager.addTurn("You", "Short")
        val window = manager.buildContextWindow()

        assertTrue(window.utilizationPercent > 0f)
        assertTrue(window.utilizationPercent < 100f)
        assertEquals(0, window.summarizedTurnCount)
    }

    @Test
    fun testContextSummarizationTriggered() {
        // Use a very small context to trigger summarization
        val smallManager = AIContextManager(
            maxContextTokens = 200,
            reservedResponseTokens = 50,
            summaryTriggerRatio = 0.5f
        )

        // Add enough turns to trigger summarization
        for (i in 1..20) {
            smallManager.addTurn("You", "This is a relatively long message number $i that should eventually trigger context summarization")
            smallManager.addTurn("Tron AI", "This is a detailed response number $i with lots of content to fill up the context window")
        }

        // After many turns, context should have been compacted
        val stats = smallManager.getStats()
        val summarizationCount = stats["summarization_count"] as Int
        assertTrue("Expected summarization to have occurred", summarizationCount > 0)
    }

    @Test
    fun testContextEntryMetadata() {
        manager.addTurn("You", "Test", mapOf("source" to "chat"))
        val window = manager.buildContextWindow()
        assertEquals("chat", window.recentTurns[0].metadata["source"])
    }
}
