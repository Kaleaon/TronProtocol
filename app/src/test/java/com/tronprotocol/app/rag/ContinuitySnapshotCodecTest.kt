package com.tronprotocol.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            consolidationStatsJson = "{\"total_consolidations\":1}",
            constitutionalMemoryJson = "{\"version\":1}",
            notes = "continuity test"
        )

        val encoded = ContinuitySnapshotCodec.encode(snapshot)
        val decoded = ContinuitySnapshotCodec.decodeWithMigration(encoded)

        assertNotNull(decoded)
        assertFalse(decoded?.wasMigrated ?: true)
        assertEquals("v2->v2", decoded?.migrationPath)
        assertEquals("snap_1", decoded?.snapshot?.snapshotId)
        assertEquals("ai_main", decoded?.snapshot?.aiId)
        assertEquals(123456789L, decoded?.snapshot?.createdAtMs)
        assertEquals("[{\"chunkId\":\"a\"}]", decoded?.snapshot?.ragChunksJson)
        assertEquals("[{\"emotion\":\"NEUTRAL\"}]", decoded?.snapshot?.emotionalHistoryJson)
        assertEquals("{\"curiosity\":0.7}", decoded?.snapshot?.personalityTraitsJson)
        assertEquals("{\"total_consolidations\":1}", decoded?.snapshot?.consolidationStatsJson)
        assertEquals("{\"version\":1}", decoded?.snapshot?.constitutionalMemoryJson)
        assertEquals("continuity test", decoded?.snapshot?.notes)
        assertTrue(decoded?.normalizedPayload?.contains("\"schemaVersion\":2") == true)
    }

    @Test
    fun decodeWithMigration_fixtureV1_transformsToCurrentSchema() {
        val fixture = loadFixture("fixtures/rag/continuity_snapshot_v1.json")

        val decoded = ContinuitySnapshotCodec.decodeWithMigration(fixture)

        assertNotNull(decoded)
        assertTrue(decoded?.wasMigrated == true)
        assertEquals("v1->v2", decoded?.migrationPath)
        assertEquals("legacy_snap_01", decoded?.snapshot?.snapshotId)
        assertEquals("legacy-ai", decoded?.snapshot?.aiId)
        assertEquals(1711111111111L, decoded?.snapshot?.createdAtMs)
        assertEquals("migrated from legacy fixture", decoded?.snapshot?.notes)
        assertTrue(decoded?.normalizedPayload?.contains("\"schemaVersion\":2") == true)
    }

    @Test
    fun decodeWithMigration_fixtureCorrupted_returnsNull() {
        val fixture = loadFixture("fixtures/rag/continuity_snapshot_corrupted.json")
        assertNull(ContinuitySnapshotCodec.decodeWithMigration(fixture))
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

    private fun loadFixture(path: String): String {
        return javaClass.classLoader?.getResource(path)?.readText()
            ?: error("Fixture not found: $path")
    }
}
