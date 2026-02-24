package com.tronprotocol.app.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionRecognizerTest {

    private lateinit var recognizer: ExpressionRecognizer

    @Before
    fun setUp() {
        recognizer = ExpressionRecognizer()
        // Initialize in geometric mode (no model file needed)
        recognizer.initialize()
    }

    @Test
    fun testInitializationGeometricMode() {
        assertTrue(recognizer.isReady)
    }

    @Test
    fun testAuToBlendshapeMappingExists() {
        // Verify all AU mappings point to valid blendshape names
        ExpressionRecognizer.AU_TO_BLENDSHAPE.forEach { (au, blendshapes) ->
            assertTrue("AU mapping '$au' should have blendshapes", blendshapes.isNotEmpty())
            blendshapes.forEach { bs ->
                assertTrue(
                    "Blendshape '$bs' should be in A2BS standard names",
                    A2BSEngine.BLENDSHAPE_NAMES.contains(bs)
                )
            }
        }
    }

    @Test
    fun testExpressionCategories() {
        // All 7 expression types should be defined
        val expressions = ExpressionRecognizer.Expression.entries
        assertEquals(7, expressions.size)
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.NEUTRAL })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.HAPPY })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.SAD })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.ANGRY })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.SURPRISED })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.DISGUSTED })
        assertTrue(expressions.any { it == ExpressionRecognizer.Expression.FEARFUL })
    }

    @Test
    fun testExpressionResultTopExpressions() {
        val weights = mapOf(
            ExpressionRecognizer.Expression.HAPPY to 0.6f,
            ExpressionRecognizer.Expression.NEUTRAL to 0.2f,
            ExpressionRecognizer.Expression.SAD to 0.1f,
            ExpressionRecognizer.Expression.ANGRY to 0.05f,
            ExpressionRecognizer.Expression.SURPRISED to 0.03f,
            ExpressionRecognizer.Expression.DISGUSTED to 0.01f,
            ExpressionRecognizer.Expression.FEARFUL to 0.01f
        )

        val result = ExpressionRecognizer.ExpressionResult(
            primaryExpression = ExpressionRecognizer.Expression.HAPPY,
            confidence = 0.6f,
            expressionWeights = weights,
            actionUnits = emptyMap(),
            inferenceTimeMs = 5
        )

        val top3 = result.topExpressions(3)
        assertEquals(3, top3.size)
        assertEquals(ExpressionRecognizer.Expression.HAPPY, top3[0].first)
        assertEquals(0.6f, top3[0].second, 0.001f)
    }

    @Test
    fun testExpressionResultToBlendshapeWeights() {
        val actionUnits = mapOf(
            "AU12_lip_corner_puller" to 0.8f,
            "AU6_cheek_raise" to 0.5f,
            "AU26_jaw_drop" to 0.3f
        )

        val result = ExpressionRecognizer.ExpressionResult(
            primaryExpression = ExpressionRecognizer.Expression.HAPPY,
            confidence = 0.8f,
            expressionWeights = emptyMap(),
            actionUnits = actionUnits,
            inferenceTimeMs = 5
        )

        val blendshapes = result.toBlendshapeWeights()

        // AU12 maps to mouthSmileLeft and mouthSmileRight
        assertTrue(blendshapes.containsKey("mouthSmileLeft"))
        assertTrue(blendshapes.containsKey("mouthSmileRight"))
        assertEquals(0.8f, blendshapes["mouthSmileLeft"]!!, 0.001f)

        // AU6 maps to cheekSquintLeft and cheekSquintRight
        assertTrue(blendshapes.containsKey("cheekSquintLeft"))
        assertEquals(0.5f, blendshapes["cheekSquintLeft"]!!, 0.001f)

        // AU26 maps to jawOpen
        assertTrue(blendshapes.containsKey("jawOpen"))
        assertEquals(0.3f, blendshapes["jawOpen"]!!, 0.001f)
    }

    @Test
    fun testRelease() {
        recognizer.release()
        // After release, geometric mode should still report ready (no native resources)
        // but the model-based classifier should be gone
    }
}
