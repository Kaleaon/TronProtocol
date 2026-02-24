package com.tronprotocol.app.wisdom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReflectionCycleTest {

    private lateinit var context: Context
    private lateinit var reflectionCycle: ReflectionCycle

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reflectionCycle = ReflectionCycle(context)
    }

    @Test
    fun initialPhase_isIdle() {
        assertEquals(
            "Initial phase should be IDLE",
            ReflectionCycle.ReflectionPhase.IDLE,
            reflectionCycle.currentPhase
        )
        assertFalse(
            "Should not be reflecting initially",
            reflectionCycle.isReflecting
        )
    }

    @Test
    fun runCycle_producesOutput() {
        // Record some events to process
        reflectionCycle.recordEvent(
            ReflectionCycle.ReflectionEvent(
                type = "emotional_peak",
                description = "High emotional weight detected during conversation",
                emotionalWeight = 0.8f,
                emotionalThemes = mapOf("trust" to 0.7f, "fear" to 0.3f)
            )
        )
        reflectionCycle.recordEvent(
            ReflectionCycle.ReflectionEvent(
                type = "coherence_drop",
                description = "Coherence dropped during complex reasoning",
                emotionalWeight = 0.5f,
                emotionalThemes = mapOf("frustration" to 0.6f)
            )
        )

        // Phase 1: Review
        val reviewEvents = reflectionCycle.executeReview()
        assertNotNull("Review should return events", reviewEvents)
        assertEquals(
            "Phase should be REVIEW after executeReview",
            ReflectionCycle.ReflectionPhase.REVIEW,
            reflectionCycle.currentPhase
        )

        // Phase 2: Sustained Attention
        val attentionResult = reflectionCycle.executeSustainedAttention(reviewEvents)
        assertNotNull("Attention result should not be null", attentionResult)
        assertEquals(
            "Phase should be SUSTAINED_ATTENTION",
            ReflectionCycle.ReflectionPhase.SUSTAINED_ATTENTION,
            reflectionCycle.currentPhase
        )

        // Phase 3: Integration
        val integrationResult = reflectionCycle.executeIntegration(
            attentionResult,
            "I learned that trust should be calibrated gradually"
        )
        assertNotNull("Integration result should not be null", integrationResult)
        assertEquals(
            "Phase should be INTEGRATION",
            ReflectionCycle.ReflectionPhase.INTEGRATION,
            reflectionCycle.currentPhase
        )

        // Phase 4: Uncertainty Preservation
        val result = reflectionCycle.executeUncertaintyPreservation(
            integrationResult,
            listOf("How should I handle conflicting emotional signals?")
        )
        assertNotNull("Final reflection result should not be null", result)
        assertTrue("Events processed should be > 0", result.eventsProcessed > 0)
        assertEquals(
            "Phase should return to IDLE after completion",
            ReflectionCycle.ReflectionPhase.IDLE,
            reflectionCycle.currentPhase
        )
    }
}
