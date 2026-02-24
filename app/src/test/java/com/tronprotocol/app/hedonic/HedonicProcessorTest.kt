package com.tronprotocol.app.hedonic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tronprotocol.app.affect.AffectState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HedonicProcessorTest {

    private lateinit var context: Context
    private lateinit var processor: HedonicProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = HedonicProcessor(context)
    }

    @Test
    fun initialization_doesNotThrow() {
        // If we reach here, constructor did not throw
        assertNotNull("HedonicProcessor should be created successfully", processor)
        assertNotNull("ConsentGate should be initialized", processor.consentGate)
        assertFalse("Should not be in refractory state initially", processor.isRefractory)
        assertEquals(
            "Initial phase should be BASELINE",
            HedonicProcessor.ArousalPhase.BASELINE,
            processor.currentPhase
        )
    }

    @Test
    fun hedonicProcessing_producesResult() {
        // Set up consent gate to be open
        processor.consentGate.updateSafety(0.9f)
        processor.consentGate.updateAttachment(0.9f)
        processor.consentGate.updateVolition(0.9f)

        val affectState = AffectState()
        affectState.arousal = 0.3f

        val signal = processor.processStimulus(
            zone = BodyZone.PALMS_INNER_PAWS,
            intensity = 0.5f,
            currentState = affectState
        )

        assertNotNull("processStimulus should produce a non-null signal", signal)
        assertEquals("Signal zone should match input zone", BodyZone.PALMS_INNER_PAWS, signal.zone)
        assertTrue(
            "Effective signal should be positive when gate is open and intensity > 0",
            signal.effectiveSignal > 0.0f
        )
        assertEquals("Signal intensity should match input", 0.5f, signal.intensity, 0.001f)
    }
}
