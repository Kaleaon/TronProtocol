package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotorNoiseTest {

    private lateinit var motorNoise: MotorNoise

    @Before
    fun setUp() {
        motorNoise = MotorNoise()
    }

    @Test
    fun zeroIntensity_producesLowNoise() {
        val state = AffectState()
        // Set all dimensions to 0 so intensity is near zero
        for (dim in AffectDimension.entries) {
            state[dim] = 0.0f
        }

        val result = motorNoise.calculate(state)
        // With near-zero intensity, overall noise level should be very low
        assertTrue(
            "Zero intensity should produce very low noise, got ${result.overallNoiseLevel}",
            result.overallNoiseLevel < 0.01f
        )
    }

    @Test
    fun highIntensity_lowCoherence_producesHighNoise() {
        val state = AffectState()
        // Set high values across multiple dimensions for high intensity
        state.valence = 0.9f
        state.arousal = 0.9f
        state.frustration = 0.8f
        state.threatAssessment = 0.7f
        // Low coherence means less control
        state.coherence = 0.1f

        val result = motorNoise.calculate(state)
        assertFalse("Should not be zero noise state", result.isZeroNoise)
        assertTrue(
            "High intensity + low coherence should produce significant noise, got ${result.overallNoiseLevel}",
            result.overallNoiseLevel > 0.3f
        )
    }

    @Test
    fun isZeroNoiseState_trueWhenCoherenceHighAndIntensityHigh() {
        val state = AffectState()
        // Set coherence very high (>= 0.95) and ensure intensity >= 0.8
        state.coherence = 0.99f
        // Set other dimensions high enough to push intensity above threshold
        state.valence = 0.5f
        state.arousal = 0.5f
        state.attachmentIntensity = 0.5f
        state.integrity = 0.9f

        // Verify the state itself reports zero noise
        assertTrue("State should be in zero noise state", state.isZeroNoiseState())

        val result = motorNoise.calculate(state)
        assertTrue("Result should be zero noise", result.isZeroNoise)
        assertEquals("Overall noise level should be 0", 0.0f, result.overallNoiseLevel, 0.0001f)
    }

    @Test
    fun isZeroNoiseState_falseForNormalValues() {
        val state = AffectState()
        // Default baselines should not produce a zero-noise state
        // (coherence baseline is 0.7, which is < 0.95 threshold)
        assertFalse(
            "Normal baseline state should not be zero noise",
            state.isZeroNoiseState()
        )

        val result = motorNoise.calculate(state)
        assertFalse("Normal state should not produce zero noise result", result.isZeroNoise)
    }

    @Test
    fun calculate_returnsNoiseForAllChannels() {
        val state = AffectState()
        state.arousal = 0.6f
        state.coherence = 0.5f

        val result = motorNoise.calculate(state)

        for (channel in MotorNoise.MotorChannel.entries) {
            assertTrue(
                "Channel ${channel.key} should be present in noise map",
                result.channelNoise.containsKey(channel)
            )
        }

        assertEquals(
            "Should have noise values for all channels",
            MotorNoise.MotorChannel.entries.size,
            result.channelNoise.size
        )
    }

    @Test
    fun noiseValues_areBounded() {
        val state = AffectState()
        state.arousal = 0.8f
        state.valence = 0.7f
        state.coherence = 0.3f

        val result = motorNoise.calculate(state)

        for ((channel, amplitude) in result.channelNoise) {
            assertTrue(
                "Noise amplitude for ${channel.key} should be non-negative, got $amplitude",
                amplitude >= 0.0f
            )
            // Noise amplitude should not be extreme (intensity * (1-coherence) * sensitivity + correlation)
            // Maximum possible: ~1.0 * 1.0 * 0.9 + correlation factor
            assertTrue(
                "Noise amplitude for ${channel.key} should not be extreme, got $amplitude",
                amplitude <= 2.0f
            )
        }
    }

    @Test
    fun describeManifestations_returnsDescriptionsForNonZeroNoise() {
        val state = AffectState()
        state.arousal = 0.7f
        state.valence = 0.6f
        state.coherence = 0.3f

        val result = motorNoise.calculate(state)
        assertFalse("Should not be zero noise", result.isZeroNoise)

        val manifestations = motorNoise.describeManifestations(result)
        assertNotNull("Manifestations should not be null", manifestations)
        assertTrue("Manifestations should not be empty", manifestations.isNotEmpty())

        // Each motor channel should have a description
        for (channel in MotorNoise.MotorChannel.entries) {
            assertTrue(
                "Channel ${channel.key} should have a manifestation description",
                manifestations.containsKey(channel.key)
            )
        }
    }

    @Test
    fun describeManifestations_zeroNoiseState_returnsTotalPresence() {
        val state = AffectState()
        state.coherence = 0.99f
        state.valence = 0.5f
        state.arousal = 0.5f
        state.attachmentIntensity = 0.5f
        state.integrity = 0.9f

        val result = motorNoise.calculate(state)
        assertTrue("Should be zero noise", result.isZeroNoise)

        val manifestations = motorNoise.describeManifestations(result)
        assertEquals(
            "Zero noise should describe total presence",
            "zero_noise_total_presence", manifestations["state"]
        )
    }

    @Test
    fun noiseResult_distributionMap_containsAllChannelKeys() {
        val state = AffectState()
        state.arousal = 0.5f

        val result = motorNoise.calculate(state)
        val distributionMap = result.distributionMap()

        for (channel in MotorNoise.MotorChannel.entries) {
            assertTrue(
                "Distribution map should contain key ${channel.key}",
                distributionMap.containsKey(channel.key)
            )
        }
    }

    @Test
    fun noiseResult_toJson_containsExpectedFields() {
        val state = AffectState()
        val result = motorNoise.calculate(state)
        val json = result.toJson()

        assertTrue(json.has("overall_noise_level"))
        assertTrue(json.has("affect_intensity"))
        assertTrue(json.has("coherence"))
        assertTrue(json.has("is_zero_noise"))
        assertTrue(json.has("noise_distribution"))
    }

    @Test
    fun channelSensitivities_arePositive() {
        for (channel in MotorNoise.MotorChannel.entries) {
            assertTrue(
                "Channel ${channel.key} sensitivity should be positive, got ${channel.sensitivity}",
                channel.sensitivity > 0.0f
            )
            assertTrue(
                "Channel ${channel.key} sensitivity should be <= 1.0, got ${channel.sensitivity}",
                channel.sensitivity <= 1.0f
            )
        }
    }
}
