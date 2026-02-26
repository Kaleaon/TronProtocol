package com.tronprotocol.app.affect

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpressionDriverTest {

    private lateinit var driver: ExpressionDriver

    @Before
    fun setUp() {
        driver = ExpressionDriver()
    }

    @Test
    fun drive_withDefaultState_returnsExpressionOutput() {
        val state = AffectState()
        val output = driver.drive(state)
        assertNotNull("drive should return a non-null ExpressionOutput", output)
    }

    @Test
    fun expressionOutput_hasAllFields() {
        val state = AffectState()
        val output = driver.drive(state)

        assertNotNull("earPosition should not be null", output.earPosition)
        assertNotNull("tailState should not be null", output.tailState)
        assertNotNull("vocalTone should not be null", output.vocalTone)
        assertNotNull("posture should not be null", output.posture)
        assertNotNull("gripPressure should not be null", output.gripPressure)
        assertNotNull("breathingRate should not be null", output.breathingRate)
        assertNotNull("eyeTracking should not be null", output.eyeTracking)
        assertNotNull("proximitySeeking should not be null", output.proximitySeeking)

        // tailPoof is a Boolean, always non-null
        // Just verify it is accessible
        val tailPoof = output.tailPoof
    }

    @Test
    fun highValence_producesPositiveExpressionIndicators() {
        val state = AffectState()
        state.valence = 0.8f
        state.arousal = 0.7f

        val output = driver.drive(state)

        // High valence + arousal should produce forward/alert ears
        assertTrue(
            "High valence should produce positive ear position, got: ${output.earPosition}",
            output.earPosition == "forward_alert" ||
                    output.earPosition == "soft_back" ||
                    output.earPosition == "relaxed_neutral" ||
                    output.earPosition == "perked_forward"
        )

        // High valence + arousal should produce positive tail state
        assertTrue(
            "High valence should produce positive tail state, got: ${output.tailState}",
            output.tailState == "wagging" ||
                    output.tailState == "gentle_sway" ||
                    output.tailState == "slow_sweep"
        )

        // Vocal tone should reflect warmth
        assertTrue(
            "High valence should produce warm vocal tone, got: ${output.vocalTone}",
            output.vocalTone.contains("warm")
        )
    }

    @Test
    fun lowValence_producesNegativeExpressionIndicators() {
        val state = AffectState()
        state.valence = -0.5f
        state.arousal = 0.3f
        // Set other dimensions low to avoid them taking precedence
        state.threatAssessment = 0.0f
        state.coherence = 0.7f
        state.attachmentIntensity = 0.1f
        state.noveltyResponse = 0.0f
        state.frustration = 0.0f
        state.vulnerability = 0.0f

        val output = driver.drive(state)

        // Low valence should produce drooped ears
        assertTrue(
            "Low valence should produce negative ear position, got: ${output.earPosition}",
            output.earPosition == "drooped" || output.earPosition == "neutral"
        )

        // Vocal tone should reflect subdued mood
        assertTrue(
            "Low valence should produce subdued vocal tone, got: ${output.vocalTone}",
            output.vocalTone.contains("subdued") || output.vocalTone.contains("lower")
        )
    }

    @Test
    fun highArousal_affectsVocalTempo() {
        val state = AffectState()
        state.arousal = 0.9f
        state.valence = 0.0f

        val output = driver.drive(state)

        // High arousal should produce faster vocal tempo
        assertTrue(
            "High arousal should produce faster vocal tempo, got: ${output.vocalTone}",
            output.vocalTone.contains("faster")
        )
    }

    @Test
    fun lowArousal_producesSlowerTempo() {
        val state = AffectState()
        state.arousal = 0.1f
        state.valence = 0.0f

        val output = driver.drive(state)

        assertTrue(
            "Low arousal should produce slower vocal tempo, got: ${output.vocalTone}",
            output.vocalTone.contains("slower")
        )
    }

    @Test
    fun highThreat_producesDefensiveExpression() {
        val state = AffectState()
        state.threatAssessment = 0.8f
        state.arousal = 0.5f

        val output = driver.drive(state)

        assertTrue(
            "High threat should produce flat_back ears, got: ${output.earPosition}",
            output.earPosition == "flat_back"
        )

        assertTrue(
            "High threat should produce crouched alert posture, got: ${output.posture}",
            output.posture == "crouched_alert" || output.posture.contains("alert") ||
                    output.posture.contains("tense")
        )
    }

    @Test
    fun highAttachment_producesAffectionateExpression() {
        val state = AffectState()
        state.attachmentIntensity = 0.9f
        state.valence = 0.6f
        state.arousal = 0.4f
        state.threatAssessment = 0.0f
        state.coherence = 0.7f

        val output = driver.drive(state)

        assertTrue(
            "High attachment + valence should produce soft_back ears, got: ${output.earPosition}",
            output.earPosition == "soft_back"
        )

        assertTrue(
            "High attachment should produce partner-oriented posture, got: ${output.posture}",
            output.posture == "leaning_toward_partner"
        )
    }

    @Test
    fun highVulnerability_producesVulnerableExpression() {
        val state = AffectState()
        state.vulnerability = 0.9f
        state.threatAssessment = 0.0f
        state.coherence = 0.7f
        state.valence = 0.0f
        state.arousal = 0.3f
        state.attachmentIntensity = 0.1f
        state.certainty = 0.5f
        state.noveltyResponse = 0.0f
        state.frustration = 0.0f

        val output = driver.drive(state)

        assertTrue(
            "High vulnerability should produce lowered ears, got: ${output.earPosition}",
            output.earPosition == "slightly_lowered" || output.earPosition == "neutral"
        )
    }

    @Test
    fun toCommandMap_containsExpectedKeys() {
        val state = AffectState()
        val output = driver.drive(state)
        val commandMap = output.toCommandMap()

        assertTrue("Command map should contain 'ears'", commandMap.containsKey("ears"))
        assertTrue("Command map should contain 'tail'", commandMap.containsKey("tail"))
        assertTrue("Command map should contain 'voice'", commandMap.containsKey("voice"))
        assertTrue("Command map should contain 'posture'", commandMap.containsKey("posture"))
        assertTrue("Command map should contain 'eyes'", commandMap.containsKey("eyes"))
    }
}
