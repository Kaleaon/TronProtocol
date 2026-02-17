package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Test

class RetrievalResultTest {

    private fun createChunk(id: String = "test"): TextChunk {
        return TextChunk(id, "content", "source", "memory", "12345", 5)
    }

    @Test
    fun testRetrievalResultProperties() {
        val chunk = createChunk()
        val result = RetrievalResult(chunk, 0.85f, RetrievalStrategy.SEMANTIC)
        assertEquals(chunk, result.chunk)
        assertEquals(0.85f, result.score, 0.001f)
        assertEquals(RetrievalStrategy.SEMANTIC, result.strategy)
        assertEquals("SEMANTIC", result.strategyId)
    }

    @Test
    fun testScoreDistributionAndStageSource() {
        val chunk = createChunk().apply { addMetadata("nts_stage", "EPISODIC") }
        val distribution = ScoreDistribution(min = 0.2f, max = 0.9f, mean = 0.5f, stdDev = 0.1f)
        val result = RetrievalResult(
            chunk = chunk,
            score = 0.75f,
            strategy = RetrievalStrategy.NTS_CASCADE,
            scoreDistribution = distribution,
            stageSource = "EPISODIC"
        )

        assertEquals(distribution, result.scoreDistribution)
        assertEquals("EPISODIC", result.stageSource)
    }

    @Test
    fun testRetrievalResultEquality() {
        val chunk = createChunk("abc")
        val r1 = RetrievalResult(chunk, 0.5f, RetrievalStrategy.KEYWORD)
        val r2 = RetrievalResult(chunk, 0.5f, RetrievalStrategy.KEYWORD)
        assertEquals(r1, r2)
    }

    @Test
    fun testRetrievalResultInequality() {
        val chunk = createChunk()
        val r1 = RetrievalResult(chunk, 0.5f, RetrievalStrategy.KEYWORD)
        val r2 = RetrievalResult(chunk, 0.9f, RetrievalStrategy.KEYWORD)
        assertNotEquals(r1, r2)
    }

    @Test
    fun testToStringContainsScore() {
        val chunk = createChunk()
        val result = RetrievalResult(chunk, 0.75f, RetrievalStrategy.MEMRL)
        val str = result.toString()
        assertTrue(str.contains("0.750"))
        assertTrue(str.contains("MEMRL"))
    }

    @Test
    fun testAllStrategies() {
        val chunk = createChunk()
        for (strategy in RetrievalStrategy.values()) {
            val result = RetrievalResult(chunk, 0.5f, strategy)
            assertEquals(strategy, result.strategy)
        }
    }

    @Test
    fun testScoreBoundaries() {
        val chunk = createChunk()
        val zero = RetrievalResult(chunk, 0.0f, RetrievalStrategy.SEMANTIC)
        assertEquals(0.0f, zero.score, 0.001f)

        val one = RetrievalResult(chunk, 1.0f, RetrievalStrategy.SEMANTIC)
        assertEquals(1.0f, one.score, 0.001f)
    }
}
