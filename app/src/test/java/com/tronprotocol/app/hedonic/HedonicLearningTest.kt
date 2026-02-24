package com.tronprotocol.app.hedonic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HedonicLearningTest {

    private lateinit var context: Context
    private lateinit var learning: HedonicLearning

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        learning = HedonicLearning(context)
    }

    @Test
    fun initialization_doesNotThrow() {
        // If we reach here, constructor did not throw
        assertNotNull("HedonicLearning should be created successfully", learning)
        // Initial learned weights should be zero (no prior experience)
        val initialWeight = learning.getLearnedWeight(BodyZone.PALMS_INNER_PAWS)
        assertEquals(
            "Initial learned weight should be 0.0",
            0.0f,
            initialWeight,
            0.001f
        )
    }

    @Test
    fun learningFromPositiveFeedback_adjustsWeights() {
        val zone = BodyZone.INNER_EARS
        val initialWeight = learning.getLearnedWeight(zone)

        // Simulate a positive encounter: open gate + positive final valence
        learning.updateWeight(zone, finalValence = 0.8f, gateWasOpen = true)

        val updatedWeight = learning.getLearnedWeight(zone)
        assertTrue(
            "Positive feedback with open gate should increase learned weight. " +
                    "Initial=$initialWeight, Updated=$updatedWeight",
            updatedWeight > initialWeight
        )
    }
}
