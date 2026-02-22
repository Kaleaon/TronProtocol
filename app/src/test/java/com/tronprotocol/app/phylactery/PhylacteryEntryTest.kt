package com.tronprotocol.app.phylactery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhylacteryEntryTest {

    @Test
    fun testEntryCreation() {
        val entry = PhylacteryEntry(
            id = "test_001",
            tier = MemoryTier.EPISODIC,
            content = "Test memory content"
        )
        assertEquals("test_001", entry.id)
        assertEquals(MemoryTier.EPISODIC, entry.tier)
        assertEquals("Test memory content", entry.content)
        assertTrue(entry.timestamp > 0)
    }

    @Test
    fun testDefaultQValue() {
        val entry = PhylacteryEntry(id = "test", tier = MemoryTier.WORKING, content = "c")
        assertEquals(0.5f, entry.qValue, 0.001f)
    }

    @Test
    fun testDriftScoreDefault() {
        val entry = PhylacteryEntry(id = "test", tier = MemoryTier.WORKING, content = "c")
        assertEquals(0.0f, entry.driftScore, 0.001f)
    }

    @Test
    fun testRetrievalCountDefault() {
        val entry = PhylacteryEntry(id = "test", tier = MemoryTier.WORKING, content = "c")
        assertEquals(0, entry.retrievalCount)
    }

    @Test
    fun testContentHash() {
        val entry1 = PhylacteryEntry(id = "a", tier = MemoryTier.WORKING, content = "hello")
        val entry2 = PhylacteryEntry(id = "b", tier = MemoryTier.WORKING, content = "hello")
        assertEquals(entry1.contentHash, entry2.contentHash)

        val entry3 = PhylacteryEntry(id = "c", tier = MemoryTier.WORKING, content = "world")
        assertNotEquals(entry1.contentHash, entry3.contentHash)
    }

    @Test
    fun testContentHashLength() {
        val hash = PhylacteryEntry.computeHash("test")
        assertEquals(64, hash.length) // SHA-256 hex = 64 chars
    }

    @Test
    fun testJsonRoundTrip() {
        val entry = PhylacteryEntry(
            id = "roundtrip_001",
            tier = MemoryTier.SEMANTIC,
            content = "Knowledge fact",
            sessionId = "session_abc",
            emotionalSnapshot = mapOf("valence" to 0.5f, "arousal" to 0.3f)
        )
        entry.qValue = 0.8f
        entry.driftScore = 0.1f
        entry.retrievalCount = 5

        val json = entry.toJson()
        val restored = PhylacteryEntry.fromJson(json)

        assertEquals(entry.id, restored.id)
        assertEquals(entry.tier, restored.tier)
        assertEquals(entry.content, restored.content)
        assertEquals(entry.sessionId, restored.sessionId)
        assertEquals(entry.qValue, restored.qValue, 0.001f)
        assertEquals(entry.driftScore, restored.driftScore, 0.001f)
        assertEquals(entry.retrievalCount, restored.retrievalCount)
    }

    @Test
    fun testEqualityById() {
        val entry1 = PhylacteryEntry(id = "same_id", tier = MemoryTier.WORKING, content = "a")
        val entry2 = PhylacteryEntry(id = "same_id", tier = MemoryTier.EPISODIC, content = "b")
        assertEquals(entry1, entry2) // Same ID = equal
    }

    @Test
    fun testInequalityByDifferentId() {
        val entry1 = PhylacteryEntry(id = "id1", tier = MemoryTier.WORKING, content = "a")
        val entry2 = PhylacteryEntry(id = "id2", tier = MemoryTier.WORKING, content = "a")
        assertNotEquals(entry1, entry2) // Different ID = not equal
    }

    @Test
    fun testMetadata() {
        val entry = PhylacteryEntry(
            id = "meta_test",
            tier = MemoryTier.SEMANTIC,
            content = "test",
            metadata = mutableMapOf("category" to "facts", "source" to "test")
        )
        assertEquals("facts", entry.metadata["category"])
        assertEquals("test", entry.metadata["source"])
    }
}
