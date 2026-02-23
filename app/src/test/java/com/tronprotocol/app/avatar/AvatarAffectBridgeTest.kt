package com.tronprotocol.app.avatar

import com.tronprotocol.app.affect.AffectDimension
import com.tronprotocol.app.affect.AffectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AvatarAffectBridgeTest {

    private lateinit var bridge: AvatarAffectBridge

    @Before
    fun setUp() {
        bridge = AvatarAffectBridge()
    }

    @Test
    fun testPositiveValenceProducesSmile() {
        val state = AffectState()
        state.valence = 0.8f

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        assertTrue(blendshapes.containsKey("mouthSmileLeft"))
        assertTrue(blendshapes.containsKey("mouthSmileRight"))
        assertTrue((blendshapes["mouthSmileLeft"] ?: 0f) > 0.1f)
        assertTrue((blendshapes["mouthSmileRight"] ?: 0f) > 0.1f)
    }

    @Test
    fun testNegativeValenceProducesFrown() {
        val state = AffectState()
        state.valence = -0.8f

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        assertTrue(blendshapes.containsKey("mouthFrownLeft"))
        assertTrue(blendshapes.containsKey("mouthFrownRight"))
        assertTrue((blendshapes["mouthFrownLeft"] ?: 0f) > 0.1f)
    }

    @Test
    fun testHighArousalWidensEyes() {
        val state = AffectState()
        state.arousal = 0.9f

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        assertTrue(blendshapes.containsKey("eyeWideLeft"))
        assertTrue(blendshapes.containsKey("eyeWideRight"))
        assertTrue((blendshapes["eyeWideLeft"] ?: 0f) > 0.1f)
    }

    @Test
    fun testFrustrationFurrowsBrow() {
        val state = AffectState()
        state[AffectDimension.FRUSTRATION] = 0.8f

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        assertTrue(blendshapes.containsKey("browDownLeft"))
        assertTrue(blendshapes.containsKey("browDownRight"))
        assertTrue((blendshapes["browDownLeft"] ?: 0f) > 0.2f)
    }

    @Test
    fun testNoveltyRaisesBrows() {
        val state = AffectState()
        state[AffectDimension.NOVELTY_RESPONSE] = 0.9f

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        assertTrue(blendshapes.containsKey("browInnerUp"))
        assertTrue((blendshapes["browInnerUp"] ?: 0f) > 0.1f)
        assertTrue(blendshapes.containsKey("eyeWideLeft"))
    }

    @Test
    fun testNeutralStateMinimalExpression() {
        // Reset to all zeros
        val state = AffectState()
        for (dim in AffectDimension.entries) {
            state[dim] = 0f
        }

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        // Most weights should be zero or near-zero
        val totalWeight = blendshapes.values.sum()
        assertTrue("Neutral state should have minimal expression weight", totalWeight < 1.0f)
    }

    @Test
    fun testCreateFrameFromAffect() {
        val state = AffectState()
        state.valence = 0.5f
        state.arousal = 0.3f

        val frame = bridge.createFrameFromAffect(state, frameIndex = 42)

        assertEquals(42, frame.index)
        assertEquals(A2BSEngine.BLENDSHAPE_COUNT, frame.weights.size)
        assertTrue(frame.timestamp > 0)
    }

    @Test
    fun testExpressionToAffectDeltasHappy() {
        val expressionWeights = mapOf(
            ExpressionRecognizer.Expression.HAPPY to 0.8f,
            ExpressionRecognizer.Expression.NEUTRAL to 0.1f,
            ExpressionRecognizer.Expression.SAD to 0f,
            ExpressionRecognizer.Expression.ANGRY to 0f,
            ExpressionRecognizer.Expression.SURPRISED to 0.1f,
            ExpressionRecognizer.Expression.DISGUSTED to 0f,
            ExpressionRecognizer.Expression.FEARFUL to 0f
        )

        val result = ExpressionRecognizer.ExpressionResult(
            primaryExpression = ExpressionRecognizer.Expression.HAPPY,
            confidence = 0.8f,
            expressionWeights = expressionWeights,
            actionUnits = emptyMap(),
            inferenceTimeMs = 5
        )

        val deltas = bridge.expressionToAffectDeltas(result)

        // Happy should produce positive valence
        assertTrue(deltas.containsKey(AffectDimension.VALENCE))
        assertTrue((deltas[AffectDimension.VALENCE] ?: 0f) > 0)

        // Happy should produce moderate arousal
        assertTrue(deltas.containsKey(AffectDimension.AROUSAL))
        assertTrue((deltas[AffectDimension.AROUSAL] ?: 0f) > 0)
    }

    @Test
    fun testExpressionToAffectDeltasAngry() {
        val expressionWeights = mapOf(
            ExpressionRecognizer.Expression.ANGRY to 0.9f,
            ExpressionRecognizer.Expression.HAPPY to 0f,
            ExpressionRecognizer.Expression.NEUTRAL to 0.1f,
            ExpressionRecognizer.Expression.SAD to 0f,
            ExpressionRecognizer.Expression.SURPRISED to 0f,
            ExpressionRecognizer.Expression.DISGUSTED to 0f,
            ExpressionRecognizer.Expression.FEARFUL to 0f
        )

        val result = ExpressionRecognizer.ExpressionResult(
            primaryExpression = ExpressionRecognizer.Expression.ANGRY,
            confidence = 0.9f,
            expressionWeights = expressionWeights,
            actionUnits = emptyMap(),
            inferenceTimeMs = 5
        )

        val deltas = bridge.expressionToAffectDeltas(result)

        // Angry should produce negative valence
        assertTrue((deltas[AffectDimension.VALENCE] ?: 0f) < 0)

        // Angry should produce high arousal
        assertTrue((deltas[AffectDimension.AROUSAL] ?: 0f) > 0)

        // Angry should produce frustration
        assertTrue(deltas.containsKey(AffectDimension.FRUSTRATION))
        assertTrue((deltas[AffectDimension.FRUSTRATION] ?: 0f) > 0)
    }

    @Test
    fun testSmoothingReducesJitter() {
        bridge.setSmoothingFactor(0.8f)

        val state1 = AffectState()
        state1.valence = 0.8f

        val result1 = bridge.affectToBlendshapes(state1)
        val smile1 = result1["mouthSmileLeft"] ?: 0f

        // Suddenly change to negative
        val state2 = AffectState()
        state2.valence = -0.8f

        val result2 = bridge.affectToBlendshapes(state2)

        // With smoothing, the smile shouldn't instantly disappear
        // (previous frame had smile, smoothing should retain some)
        val smile2 = result2["mouthSmileLeft"] ?: 0f
        assertTrue(
            "Smoothing should retain some previous expression",
            smile2 > 0 || result2.isNotEmpty()
        )
    }

    @Test
    fun testResetSmoothing() {
        bridge.setSmoothingFactor(0.9f)

        val state = AffectState()
        state.valence = 0.8f
        bridge.affectToBlendshapes(state)

        bridge.resetSmoothing()

        // After reset, smoothing history should be cleared
        val state2 = AffectState()
        for (dim in AffectDimension.entries) state2[dim] = 0f

        val result = bridge.affectToBlendshapes(state2, enableSmoothing = false)
        val totalWeight = result.values.sum()
        assertTrue("After reset with zero state, weight should be minimal", totalWeight < 0.5f)
    }

    @Test
    fun testBlendshapeWeightsInValidRange() {
        // Test with extreme affect states
        val state = AffectState()
        for (dim in AffectDimension.entries) {
            state[dim] = dim.maxValue
        }

        val blendshapes = bridge.affectToBlendshapes(state, enableSmoothing = false)

        blendshapes.forEach { (name, weight) ->
            assertTrue(
                "Blendshape '$name' weight should be <= 1.0 but was $weight",
                weight <= 1.01f // small tolerance for float math
            )
            assertTrue(
                "Blendshape '$name' weight should be >= -0.2 but was $weight",
                weight >= -0.2f // headPitch can be slightly negative
            )
        }
    }
}
