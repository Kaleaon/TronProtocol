package com.tronprotocol.app.drift

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriftDetectorTest {

    private lateinit var context: Context
    private lateinit var detector: DriftDetector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        detector = DriftDetector(context)
    }

    @Test
    fun identicalContent_producesNearZeroDrift() {
        val content = "The quick brown fox jumps over the lazy dog"
        val score = detector.measureDrift("entry_1", content, content)

        // Identical content should have cosine similarity ~1.0, so drift ~0.0
        assertTrue(
            "Identical content should produce near-zero drift, got ${score.driftMagnitude}",
            score.driftMagnitude < 0.05f
        )
        assertTrue(
            "Identical content should have high cosine similarity, got ${score.cosineSimilarity}",
            score.cosineSimilarity > 0.95f
        )
        assertFalse(
            "Identical content should not be flagged for review",
            score.flaggedForReview
        )
    }

    @Test
    fun completelyDifferentContent_producesHighDrift() {
        val recalled = "Mathematics algebra geometry calculus trigonometry"
        val groundTruth = "Painting sculpture pottery weaving ceramics textiles"
        val score = detector.measureDrift("entry_2", recalled, groundTruth)

        // Completely different content should have low cosine similarity, high drift
        assertTrue(
            "Completely different content should produce high drift, got ${score.driftMagnitude}",
            score.driftMagnitude > 0.3f
        )
        assertTrue(
            "Completely different content should have low cosine similarity, got ${score.cosineSimilarity}",
            score.cosineSimilarity < 0.7f
        )
    }

    @Test
    fun partiallySimilarContent_producesModerDrift() {
        val recalled = "The user enjoys hiking in the mountains and reading books"
        val groundTruth = "The user enjoys hiking in the hills and writing poetry"
        val score = detector.measureDrift("entry_3", recalled, groundTruth)

        // Partially similar content should have moderate drift
        assertTrue(
            "Partially similar content drift should be between 0 and 1, got ${score.driftMagnitude}",
            score.driftMagnitude in 0.0f..1.0f
        )
        assertTrue(
            "Partially similar content should have some similarity, got ${score.cosineSimilarity}",
            score.cosineSimilarity > 0.0f
        )
        assertTrue(
            "Partially similar content should not have perfect similarity, got ${score.cosineSimilarity}",
            score.cosineSimilarity < 1.0f
        )
    }

    @Test
    fun emptyStrings_handleGracefully() {
        // Both empty
        val score1 = detector.measureDrift("entry_4", "", "")
        assertNotNull("Should return a DriftScore for empty strings", score1)
        assertTrue(
            "Empty strings drift magnitude should be in valid range",
            score1.driftMagnitude in 0.0f..1.0f
        )

        // One empty, one not
        val score2 = detector.measureDrift("entry_5", "", "some content here")
        assertNotNull("Should return a DriftScore when recalled is empty", score2)
        assertTrue(
            "Drift magnitude should be in valid range",
            score2.driftMagnitude in 0.0f..1.0f
        )

        // The other way around
        val score3 = detector.measureDrift("entry_6", "some content here", "")
        assertNotNull("Should return a DriftScore when ground truth is empty", score3)
        assertTrue(
            "Drift magnitude should be in valid range",
            score3.driftMagnitude in 0.0f..1.0f
        )
    }
}
