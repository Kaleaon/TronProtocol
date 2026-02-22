package com.tronprotocol.app.drift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftScoreTest {

    @Test
    fun testDriftMagnitudeCalculation() {
        val score = DriftScore(
            entryId = "test",
            recalledContent = "recalled",
            groundTruthHash = "hash123",
            cosineSimilarity = 0.9f
        )
        assertEquals(0.1f, score.driftMagnitude, 0.001f)
    }

    @Test
    fun testNotFlaggedWhenSimilarityHigh() {
        val score = DriftScore(
            entryId = "test",
            recalledContent = "recalled",
            groundTruthHash = "hash123",
            cosineSimilarity = 0.95f
        )
        assertFalse(score.flaggedForReview)
        assertEquals(0.05f, score.driftMagnitude, 0.001f)
    }

    @Test
    fun testFlaggedWhenSimilarityLow() {
        // Alert threshold is 0.15 (drift magnitude), which means similarity < 0.85
        val score = DriftScore(
            entryId = "test",
            recalledContent = "recalled",
            groundTruthHash = "hash123",
            cosineSimilarity = 0.8f
        )
        assertTrue(score.flaggedForReview)
        assertEquals(0.2f, score.driftMagnitude, 0.001f)
    }

    @Test
    fun testExactMatchNoDrift() {
        val score = DriftScore(
            entryId = "test",
            recalledContent = "exact",
            groundTruthHash = "hash",
            cosineSimilarity = 1.0f
        )
        assertEquals(0.0f, score.driftMagnitude, 0.001f)
        assertFalse(score.flaggedForReview)
    }

    @Test
    fun testJsonSerialization() {
        val score = DriftScore(
            entryId = "json_test",
            recalledContent = "recalled",
            groundTruthHash = "abc123",
            cosineSimilarity = 0.75f
        )
        val json = score.toJson()
        assertEquals("json_test", json.getString("entry_id"))
        assertEquals(0.75, json.getDouble("cosine_similarity"), 0.001)
        assertEquals(0.25, json.getDouble("drift_magnitude"), 0.001)
        assertTrue(json.getBoolean("flagged_for_review"))
    }

    @Test
    fun testAlertThresholdConstant() {
        assertEquals(0.15f, DriftScore.ALERT_THRESHOLD, 0.001f)
    }
}
