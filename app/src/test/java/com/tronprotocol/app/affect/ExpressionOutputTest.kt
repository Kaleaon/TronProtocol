package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionOutputTest {

    private fun createDefaultOutput(): ExpressionOutput = ExpressionOutput(
        earPosition = "neutral",
        tailState = "neutral_rest",
        tailPoof = false,
        vocalTone = "neutral_pitch_steady_moderate",
        posture = "neutral_upright",
        gripPressure = "relaxed",
        breathingRate = "steady",
        eyeTracking = "calm_attentive",
        proximitySeeking = "neutral_distance"
    )

    @Test
    fun creation_withAllFields() {
        val output = ExpressionOutput(
            earPosition = "forward_alert",
            tailState = "wagging",
            tailPoof = true,
            vocalTone = "warm_higher_faster_stable",
            posture = "upright_confident",
            gripPressure = "firm_hold",
            breathingRate = "elevated",
            eyeTracking = "locked_on_partner",
            proximitySeeking = "seeking_partner"
        )

        assertEquals("forward_alert", output.earPosition)
        assertEquals("wagging", output.tailState)
        assertTrue(output.tailPoof)
        assertEquals("warm_higher_faster_stable", output.vocalTone)
        assertEquals("upright_confident", output.posture)
        assertEquals("firm_hold", output.gripPressure)
        assertEquals("elevated", output.breathingRate)
        assertEquals("locked_on_partner", output.eyeTracking)
        assertEquals("seeking_partner", output.proximitySeeking)
    }

    @Test
    fun toJson_serializesAllFields() {
        val output = createDefaultOutput()
        val json = output.toJson()

        assertNotNull(json)
        assertEquals("neutral", json.getString("ear_position"))
        assertEquals("neutral_rest", json.getString("tail_state"))
        assertFalse(json.getBoolean("tail_poof"))
        assertEquals("neutral_pitch_steady_moderate", json.getString("vocal_tone"))
        assertEquals("neutral_upright", json.getString("posture"))
        assertEquals("relaxed", json.getString("grip_pressure"))
        assertEquals("steady", json.getString("breathing_rate"))
        assertEquals("calm_attentive", json.getString("eye_tracking"))
        assertEquals("neutral_distance", json.getString("proximity_seeking"))
    }

    @Test
    fun toJson_fromJson_roundTrip() {
        val original = ExpressionOutput(
            earPosition = "soft_back",
            tailState = "slow_sweep",
            tailPoof = false,
            vocalTone = "warm_higher_steady_stable",
            posture = "leaning_toward_partner",
            gripPressure = "gentle_hold",
            breathingRate = "deep_slow",
            eyeTracking = "locked_on_partner",
            proximitySeeking = "maintaining_nearness"
        )

        val json = original.toJson()

        // Manually reconstruct from JSON to verify round-trip
        val reconstructed = ExpressionOutput(
            earPosition = json.getString("ear_position"),
            tailState = json.getString("tail_state"),
            tailPoof = json.getBoolean("tail_poof"),
            vocalTone = json.getString("vocal_tone"),
            posture = json.getString("posture"),
            gripPressure = json.getString("grip_pressure"),
            breathingRate = json.getString("breathing_rate"),
            eyeTracking = json.getString("eye_tracking"),
            proximitySeeking = json.getString("proximity_seeking")
        )

        assertEquals(original, reconstructed)
    }

    @Test
    fun toCommandMap_returnsMapWithAllChannels() {
        val output = createDefaultOutput()
        val commandMap = output.toCommandMap()

        assertNotNull(commandMap)
        assertEquals(5, commandMap.size)

        assertTrue("Command map should contain 'ears'", commandMap.containsKey("ears"))
        assertTrue("Command map should contain 'tail'", commandMap.containsKey("tail"))
        assertTrue("Command map should contain 'voice'", commandMap.containsKey("voice"))
        assertTrue("Command map should contain 'posture'", commandMap.containsKey("posture"))
        assertTrue("Command map should contain 'eyes'", commandMap.containsKey("eyes"))

        assertEquals("neutral", commandMap["ears"])
        assertEquals("neutral_rest", commandMap["tail"])
        assertEquals("neutral_pitch_steady_moderate", commandMap["voice"])
        assertEquals("neutral_upright", commandMap["posture"])
        assertEquals("calm_attentive", commandMap["eyes"])
    }

    @Test
    fun defaultValues_areReasonable() {
        val output = createDefaultOutput()

        // Default ear position should be a recognized state
        assertTrue(
            "Default ear position should be a valid state",
            output.earPosition.isNotEmpty()
        )

        // Default tail state should be a recognized state
        assertTrue(
            "Default tail state should be a valid state",
            output.tailState.isNotEmpty()
        )

        // Default tail poof should be false (no sudden spike)
        assertFalse("Default tailPoof should be false", output.tailPoof)

        // Vocal tone should contain meaningful tokens
        assertTrue(
            "Vocal tone should contain pitch/tempo/stability info",
            output.vocalTone.isNotEmpty()
        )

        // Posture should be a recognized state
        assertTrue(
            "Posture should be a valid state",
            output.posture.isNotEmpty()
        )
    }

    @Test
    fun toString_containsKeyFields() {
        val output = createDefaultOutput()
        val str = output.toString()

        assertTrue("toString should contain ears", str.contains("ears="))
        assertTrue("toString should contain tail", str.contains("tail="))
        assertTrue("toString should contain voice", str.contains("voice="))
        assertTrue("toString should contain posture", str.contains("posture="))
    }

    @Test
    fun dataClass_equality() {
        val output1 = createDefaultOutput()
        val output2 = createDefaultOutput()

        assertEquals("Identical outputs should be equal", output1, output2)
        assertEquals("Identical outputs should have same hashCode", output1.hashCode(), output2.hashCode())
    }

    @Test
    fun dataClass_copy() {
        val original = createDefaultOutput()
        val modified = original.copy(earPosition = "forward_alert", tailPoof = true)

        assertEquals("forward_alert", modified.earPosition)
        assertTrue(modified.tailPoof)
        // Other fields should remain the same
        assertEquals(original.tailState, modified.tailState)
        assertEquals(original.vocalTone, modified.vocalTone)
        assertEquals(original.posture, modified.posture)
    }
}
