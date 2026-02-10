package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TextChunkTest {

    private lateinit var chunk: TextChunk

    @Before
    fun setUp() {
        chunk = TextChunk(
            chunkId = "test-001",
            content = "This is test content",
            source = "test",
            sourceType = "memory",
            timestamp = System.currentTimeMillis().toString(),
            tokenCount = 5
        )
    }

    // --- Initial state ---

    @Test
    fun testInitialQValue() {
        assertEquals(0.5f, chunk.qValue, 0.001f)
    }

    @Test
    fun testInitialRetrievalCount() {
        assertEquals(0, chunk.retrievalCount)
    }

    @Test
    fun testInitialSuccessCount() {
        assertEquals(0, chunk.successCount)
    }

    @Test
    fun testInitialSuccessRate() {
        assertEquals(0.0f, chunk.getSuccessRate(), 0.001f)
    }

    // --- Q-value learning ---

    @Test
    fun testSuccessfulRetrievalIncreasesQValue() {
        val initialQ = chunk.qValue
        chunk.updateQValue(true, 0.1f)
        assertTrue(chunk.qValue > initialQ)
    }

    @Test
    fun testFailedRetrievalDecreasesQValue() {
        val initialQ = chunk.qValue
        chunk.updateQValue(false, 0.1f)
        assertTrue(chunk.qValue < initialQ)
    }

    @Test
    fun testQValueUpdateFormula() {
        // Q = 0.5 + 0.1 * (1.0 - 0.5) = 0.5 + 0.05 = 0.55
        chunk.updateQValue(true, 0.1f)
        assertEquals(0.55f, chunk.qValue, 0.001f)
    }

    @Test
    fun testQValueFailFormula() {
        // Q = 0.5 + 0.1 * (0.0 - 0.5) = 0.5 - 0.05 = 0.45
        chunk.updateQValue(false, 0.1f)
        assertEquals(0.45f, chunk.qValue, 0.001f)
    }

    @Test
    fun testQValueClampedToZero() {
        // Many failures should bring Q close to 0 but never below
        for (i in 0 until 100) {
            chunk.updateQValue(false, 0.5f)
        }
        assertTrue(chunk.qValue >= 0.0f)
    }

    @Test
    fun testQValueClampedToOne() {
        // Many successes should bring Q close to 1 but never above
        for (i in 0 until 100) {
            chunk.updateQValue(true, 0.5f)
        }
        assertTrue(chunk.qValue <= 1.0f)
    }

    @Test
    fun testRetrievalCountIncrementsOnSuccess() {
        chunk.updateQValue(true, 0.1f)
        assertEquals(1, chunk.retrievalCount)
    }

    @Test
    fun testRetrievalCountIncrementsOnFailure() {
        chunk.updateQValue(false, 0.1f)
        assertEquals(1, chunk.retrievalCount)
    }

    @Test
    fun testSuccessCountIncrementsOnSuccess() {
        chunk.updateQValue(true, 0.1f)
        assertEquals(1, chunk.successCount)
    }

    @Test
    fun testSuccessCountDoesNotIncrementOnFailure() {
        chunk.updateQValue(false, 0.1f)
        assertEquals(0, chunk.successCount)
    }

    // --- Success rate ---

    @Test
    fun testSuccessRateAllSuccesses() {
        chunk.updateQValue(true, 0.1f)
        chunk.updateQValue(true, 0.1f)
        chunk.updateQValue(true, 0.1f)
        assertEquals(1.0f, chunk.getSuccessRate(), 0.001f)
    }

    @Test
    fun testSuccessRateMixed() {
        chunk.updateQValue(true, 0.1f)
        chunk.updateQValue(false, 0.1f)
        chunk.updateQValue(true, 0.1f)
        chunk.updateQValue(false, 0.1f)
        assertEquals(0.5f, chunk.getSuccessRate(), 0.001f)
    }

    // --- Restore state ---

    @Test
    fun testRestoreMemRLState() {
        chunk.restoreMemRLState(0.8f, 10, 7)
        assertEquals(0.8f, chunk.qValue, 0.001f)
        assertEquals(10, chunk.retrievalCount)
        assertEquals(7, chunk.successCount)
    }

    @Test
    fun testRestoreClampsBadQValue() {
        chunk.restoreMemRLState(1.5f, 5, 3)
        assertEquals(1.0f, chunk.qValue, 0.001f)
    }

    @Test
    fun testRestoreClampsNegativeQValue() {
        chunk.restoreMemRLState(-0.5f, 5, 3)
        assertEquals(0.0f, chunk.qValue, 0.001f)
    }

    // --- Metadata ---

    @Test
    fun testAddMetadata() {
        chunk.addMetadata("category", "test")
        assertEquals("test", chunk.metadata["category"])
    }

    @Test
    fun testMetadataOverwrite() {
        chunk.addMetadata("key", "value1")
        chunk.addMetadata("key", "value2")
        assertEquals("value2", chunk.metadata["key"])
    }

    // --- Properties ---

    @Test
    fun testChunkId() {
        assertEquals("test-001", chunk.chunkId)
    }

    @Test
    fun testContent() {
        assertEquals("This is test content", chunk.content)
    }

    @Test
    fun testSource() {
        assertEquals("test", chunk.source)
    }

    @Test
    fun testSourceType() {
        assertEquals("memory", chunk.sourceType)
    }

    @Test
    fun testTokenCount() {
        assertEquals(5, chunk.tokenCount)
    }

    // --- toString ---

    @Test
    fun testToString() {
        val str = chunk.toString()
        assertTrue(str.contains("test-001"))
        assertTrue(str.contains("qValue="))
        assertTrue(str.contains("successRate="))
    }

    // --- Embedding ---

    @Test
    fun testEmbeddingNullByDefault() {
        assertNull(chunk.embedding)
    }

    @Test
    fun testSetEmbedding() {
        val emb = floatArrayOf(1.0f, 2.0f, 3.0f)
        chunk.embedding = emb
        assertArrayEquals(emb, chunk.embedding!!, 0.001f)
    }
}
