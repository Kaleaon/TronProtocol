package com.tronprotocol.app.affect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectStateTest {

    @Test
    fun testInitializationWithBaselines() {
        val state = AffectState()
        for (dim in AffectDimension.entries) {
            assertEquals("Dimension ${dim.key} should be initialized to baseline",
                dim.baseline, state[dim], 0.0001f)
        }
    }

    @Test
    fun testGettersAndSettersWithClamping() {
        val state = AffectState()

        for (dim in AffectDimension.entries) {
            // Test within range
            val midValue = (dim.minValue + dim.maxValue) / 2f
            state[dim] = midValue
            assertEquals("Should set value within range for ${dim.key}",
                midValue, state[dim], 0.0001f)

            // Test below min
            state[dim] = dim.minValue - 1.0f
            assertEquals("Should clamp to minValue for ${dim.key}",
                dim.minValue, state[dim], 0.0001f)

            // Test above max
            state[dim] = dim.maxValue + 1.0f
            assertEquals("Should clamp to maxValue for ${dim.key}",
                dim.maxValue, state[dim], 0.0001f)
        }
    }

    @Test
    fun testNamedProperties() {
        val state = AffectState()

        state.valence = 0.5f
        assertEquals(0.5f, state[AffectDimension.VALENCE], 0.0001f)
        assertEquals(0.5f, state.valence, 0.0001f)

        state.arousal = 0.8f
        assertEquals(0.8f, state[AffectDimension.AROUSAL], 0.0001f)
        assertEquals(0.8f, state.arousal, 0.0001f)

        state.coherence = 0.99f
        assertEquals(0.99f, state[AffectDimension.COHERENCE], 0.0001f)
    }

    @Test
    fun testIntensityCalculation() {
        val state = AffectState()
        // Reset all to 0 for easier calculation
        for (dim in AffectDimension.entries) {
            state[dim] = 0.0f
        }

        state.valence = 0.3f
        state.arousal = 0.4f
        // Intensity should be sqrt(0.3^2 + 0.4^2) = sqrt(0.09 + 0.16) = sqrt(0.25) = 0.5
        assertEquals(0.5f, state.intensity(), 0.0001f)
    }

    @Test
    fun testIsZeroNoiseState() {
        val state = AffectState()

        // thresholds: coherence >= 0.95f && intensity >= 0.8f

        // Low coherence, low intensity
        state.coherence = 0.5f
        for (dim in AffectDimension.entries) { if (dim != AffectDimension.COHERENCE) state[dim] = 0.1f }
        assertFalse(state.isZeroNoiseState())

        // High coherence, low intensity (impossible in current model as coherence contributes to intensity)
        // If coherence >= 0.95, intensity is at least 0.95, which is > 0.8

        // Low coherence, high intensity
        state.coherence = 0.5f
        state.valence = 0.9f // high intensity
        assertFalse(state.isZeroNoiseState())

        // High coherence, high intensity
        state.coherence = 0.96f
        state.valence = 0.9f
        assertTrue(state.isZeroNoiseState())
    }

    @Test
    fun testSnapshot() {
        val state = AffectState()
        state.valence = 0.5f

        val snap = state.snapshot()
        assertNotSame(state, snap)
        assertEquals(state.valence, snap.valence, 0.0001f)
        assertEquals(state.intensity(), snap.intensity(), 0.0001f)

        // Modifying original should not affect snapshot
        state.valence = 0.8f
        assertEquals(0.5f, snap.valence, 0.0001f)
    }

    @Test
    fun testJsonSerialization() {
        val state = AffectState()
        state.valence = 0.1f
        state.arousal = 0.2f

        val json = state.toJson()
        assertEquals(0.1, json.getDouble("valence"), 0.0001)
        assertEquals(0.2, json.getDouble("arousal"), 0.0001)

        val fromJson = AffectState.fromJson(json)
        assertEquals(0.1f, fromJson.valence, 0.0001f)
        assertEquals(0.2f, fromJson.arousal, 0.0001f)
    }

    @Test
    fun testToMap() {
        val state = AffectState()
        state.valence = -0.5f

        val map = state.toMap()
        assertEquals(-0.5f, map["valence"] ?: 0.0f, 0.0001f)
        assertEquals(AffectDimension.entries.size, map.size)
    }

    @Test
    fun testToString() {
        val state = AffectState()
        val str = state.toString()
        assertTrue(str.startsWith("AffectState("))
        assertTrue(str.contains("valence="))
        assertTrue(str.contains("intensity="))
    }
}
