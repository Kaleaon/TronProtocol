package com.tronprotocol.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContinuitySnapshotCodecTest {

    @Test
    fun encodeDecode_roundTrip_preservesCoreFields() {
        val snapshot = ContinuitySnapshotCodec.ContinuitySnapshot(
            snapshotId = "snap_1",
            aiId = "ai_main",
            createdAtMs = 123456789L,
            ragChunksJson = "[{\"chunkId\":\"a\"}]",
            emotionalHistoryJson = "[{\"emotion\":\"NEUTRAL\"}]",
            personalityTraitsJson = "{\"curiosity\":0.7}",
            constitutionalMemoryJson = "{\"version\":1}",
            notes = "continuity test"
        )

        val encoded = ContinuitySnapshotCodec.encode(snapshot)
        val decoded = ContinuitySnapshotCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals("snap_1", decoded?.snapshotId)
        assertEquals("ai_main", decoded?.aiId)
        assertEquals(123456789L, decoded?.createdAtMs)
        assertEquals("continuity test", decoded?.notes)
    }

    @Test
    fun decode_invalidJson_returnsNull() {
        assertNull(ContinuitySnapshotCodec.decode("not-json"))
    }

    @Test
    fun sanitizeIdentifier_stripsUnsafeChars() {
        val cleaned = ContinuitySnapshotCodec.sanitizeIdentifier("ai id/with spaces", "fallback")
        assertEquals("ai_id_with_spaces", cleaned)
    }

    @Test
    fun sanitizeIdentifier_usesFallbackForBlankInput() {
        val fallback = ContinuitySnapshotCodec.sanitizeIdentifier("   ", "fallback")
        assertEquals("fallback", fallback)
    }
}
