package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectInputTest {

    @Test
    fun creation_withSourceAndDeltas() {
        val deltas = mapOf(
            AffectDimension.VALENCE to 0.3f,
            AffectDimension.AROUSAL to 0.5f
        )
        val input = AffectInput(source = "test:source", deltas = deltas)

        assertEquals("test:source", input.source)
        assertEquals(2, input.deltas.size)
        assertEquals(0.3f, input.deltas[AffectDimension.VALENCE]!!, 0.0001f)
        assertEquals(0.5f, input.deltas[AffectDimension.AROUSAL]!!, 0.0001f)
    }

    @Test
    fun builder_patternWorks() {
        val input = AffectInput.builder("test:builder")
            .valence(0.5f)
            .arousal(0.3f)
            .build()

        assertEquals("test:builder", input.source)
        assertEquals(0.5f, input.deltas[AffectDimension.VALENCE]!!, 0.0001f)
        assertEquals(0.3f, input.deltas[AffectDimension.AROUSAL]!!, 0.0001f)
    }

    @Test
    fun builder_convenienceMethods_produceCorrectDeltas() {
        val input = AffectInput.builder("test:convenience")
            .valence(0.1f)
            .arousal(0.2f)
            .attachmentIntensity(0.3f)
            .certainty(0.4f)
            .noveltyResponse(0.5f)
            .threatAssessment(0.6f)
            .frustration(0.7f)
            .satiation(0.8f)
            .vulnerability(0.9f)
            .coherence(0.15f)
            .dominance(0.25f)
            .integrity(0.35f)
            .build()

        assertEquals(12, input.deltas.size)
        assertEquals(0.1f, input.deltas[AffectDimension.VALENCE]!!, 0.0001f)
        assertEquals(0.2f, input.deltas[AffectDimension.AROUSAL]!!, 0.0001f)
        assertEquals(0.3f, input.deltas[AffectDimension.ATTACHMENT_INTENSITY]!!, 0.0001f)
        assertEquals(0.4f, input.deltas[AffectDimension.CERTAINTY]!!, 0.0001f)
        assertEquals(0.5f, input.deltas[AffectDimension.NOVELTY_RESPONSE]!!, 0.0001f)
        assertEquals(0.6f, input.deltas[AffectDimension.THREAT_ASSESSMENT]!!, 0.0001f)
        assertEquals(0.7f, input.deltas[AffectDimension.FRUSTRATION]!!, 0.0001f)
        assertEquals(0.8f, input.deltas[AffectDimension.SATIATION]!!, 0.0001f)
        assertEquals(0.9f, input.deltas[AffectDimension.VULNERABILITY]!!, 0.0001f)
        assertEquals(0.15f, input.deltas[AffectDimension.COHERENCE]!!, 0.0001f)
        assertEquals(0.25f, input.deltas[AffectDimension.DOMINANCE]!!, 0.0001f)
        assertEquals(0.35f, input.deltas[AffectDimension.INTEGRITY]!!, 0.0001f)
    }

    @Test
    fun emptyDeltas_allowed() {
        val input = AffectInput(source = "test:empty", deltas = emptyMap())

        assertEquals("test:empty", input.source)
        assertTrue("Empty deltas should be allowed", input.deltas.isEmpty())
    }

    @Test
    fun builder_emptyDeltas_allowed() {
        val input = AffectInput.builder("test:empty_builder").build()

        assertEquals("test:empty_builder", input.source)
        assertTrue("Builder with no deltas should produce empty map", input.deltas.isEmpty())
    }

    @Test
    fun timestamp_defaultsToCurrentTime() {
        val beforeCreation = System.currentTimeMillis()
        val input = AffectInput(source = "test:timestamp", deltas = emptyMap())
        val afterCreation = System.currentTimeMillis()

        assertTrue(
            "Timestamp should be >= creation start time",
            input.timestamp >= beforeCreation
        )
        assertTrue(
            "Timestamp should be <= creation end time",
            input.timestamp <= afterCreation
        )
    }

    @Test
    fun timestamp_canBeSetExplicitly() {
        val customTimestamp = 1234567890L
        val input = AffectInput(
            source = "test:custom_ts",
            deltas = emptyMap(),
            timestamp = customTimestamp
        )

        assertEquals(customTimestamp, input.timestamp)
    }

    @Test
    fun builder_delta_genericMethod() {
        val input = AffectInput.builder("test:generic_delta")
            .delta(AffectDimension.VALENCE, 0.42f)
            .delta(AffectDimension.INTEGRITY, 0.99f)
            .build()

        assertEquals(0.42f, input.deltas[AffectDimension.VALENCE]!!, 0.0001f)
        assertEquals(0.99f, input.deltas[AffectDimension.INTEGRITY]!!, 0.0001f)
    }

    @Test
    fun dataClass_equality() {
        val deltas = mapOf(AffectDimension.VALENCE to 0.5f)
        val ts = 1000L
        val input1 = AffectInput("test", deltas, ts)
        val input2 = AffectInput("test", deltas, ts)

        assertEquals("Identical inputs should be equal", input1, input2)
        assertEquals("Identical inputs should have same hashCode", input1.hashCode(), input2.hashCode())
    }

    @Test
    fun dataClass_copy() {
        val original = AffectInput.builder("original")
            .valence(0.5f)
            .build()

        val copied = original.copy(source = "copied")
        assertEquals("copied", copied.source)
        assertEquals(original.deltas, copied.deltas)
    }

    @Test
    fun builder_negativeDeltas_allowed() {
        val input = AffectInput.builder("test:negative")
            .valence(-0.5f)
            .frustration(-0.3f)
            .build()

        assertEquals(-0.5f, input.deltas[AffectDimension.VALENCE]!!, 0.0001f)
        assertEquals(-0.3f, input.deltas[AffectDimension.FRUSTRATION]!!, 0.0001f)
    }

    @Test
    fun builder_returnsNonNull() {
        val builder = AffectInput.builder("test")
        assertNotNull(builder)

        val input = builder.build()
        assertNotNull(input)
    }
}
