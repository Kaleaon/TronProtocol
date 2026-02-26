package com.tronprotocol.app.affect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectDimensionTest {

    @Test
    fun all12Dimensions_exist() {
        val dimensions = AffectDimension.entries
        assertEquals("Should have exactly 12 dimensions", 12, dimensions.size)
    }

    @Test
    fun allExpectedDimensions_areDefined() {
        val expectedNames = listOf(
            "VALENCE", "AROUSAL", "ATTACHMENT_INTENSITY", "CERTAINTY",
            "NOVELTY_RESPONSE", "THREAT_ASSESSMENT", "FRUSTRATION", "SATIATION",
            "VULNERABILITY", "COHERENCE", "DOMINANCE", "INTEGRITY"
        )

        for (name in expectedNames) {
            val dim = AffectDimension.valueOf(name)
            assertNotNull("Dimension $name should exist", dim)
        }
    }

    @Test
    fun eachDimension_hasPositiveInertia() {
        for (dim in AffectDimension.entries) {
            assertTrue(
                "Dimension ${dim.key} inertia should be > 0, got ${dim.inertia}",
                dim.inertia > 0.0f
            )
            assertTrue(
                "Dimension ${dim.key} inertia should be <= 1.0, got ${dim.inertia}",
                dim.inertia <= 1.0f
            )
        }
    }

    @Test
    fun eachDimension_hasNonNegativeBaseline() {
        for (dim in AffectDimension.entries) {
            assertTrue(
                "Dimension ${dim.key} baseline should be >= 0, got ${dim.baseline}",
                dim.baseline >= 0.0f
            )
        }
    }

    @Test
    fun baselines_areWithinZeroToOne() {
        for (dim in AffectDimension.entries) {
            assertTrue(
                "Dimension ${dim.key} baseline should be >= 0, got ${dim.baseline}",
                dim.baseline >= 0.0f
            )
            assertTrue(
                "Dimension ${dim.key} baseline should be <= 1.0, got ${dim.baseline}",
                dim.baseline <= 1.0f
            )
        }
    }

    @Test
    fun dimensionKeys_areUnique() {
        val keys = AffectDimension.entries.map { it.key }
        assertEquals(
            "All dimension keys should be unique",
            keys.size,
            keys.toSet().size
        )
    }

    @Test
    fun dimensionNames_areUnique() {
        val names = AffectDimension.entries.map { it.name }
        assertEquals(
            "All dimension names should be unique",
            names.size,
            names.toSet().size
        )
    }

    @Test
    fun valence_baselineIs02() {
        assertEquals(
            "VALENCE baseline should be 0.2",
            0.2f, AffectDimension.VALENCE.baseline, 0.0001f
        )
    }

    @Test
    fun coherence_baselineIs07() {
        assertEquals(
            "COHERENCE baseline should be 0.7",
            0.7f, AffectDimension.COHERENCE.baseline, 0.0001f
        )
    }

    @Test
    fun integrity_hasHighInertia() {
        assertEquals(
            "INTEGRITY inertia should be 0.8",
            0.8f, AffectDimension.INTEGRITY.inertia, 0.0001f
        )

        // Also verify it is among the highest inertia values
        val maxInertia = AffectDimension.entries.maxOf { it.inertia }
        assertTrue(
            "INTEGRITY should have one of the highest inertia values",
            AffectDimension.INTEGRITY.inertia >= maxInertia - 0.1f
        )
    }

    @Test
    fun valence_hasNegativeMinValue() {
        assertEquals(
            "VALENCE minValue should be -1.0",
            -1.0f, AffectDimension.VALENCE.minValue, 0.0001f
        )
        assertEquals(
            "VALENCE maxValue should be 1.0",
            1.0f, AffectDimension.VALENCE.maxValue, 0.0001f
        )
    }

    @Test
    fun nonValenceDimensions_haveNonNegativeRange() {
        for (dim in AffectDimension.entries) {
            if (dim != AffectDimension.VALENCE) {
                assertTrue(
                    "Dimension ${dim.key} minValue should be >= 0, got ${dim.minValue}",
                    dim.minValue >= 0.0f
                )
            }
            assertTrue(
                "Dimension ${dim.key} maxValue should be > minValue",
                dim.maxValue > dim.minValue
            )
        }
    }

    @Test
    fun eachDimension_hasPositiveDecayRate() {
        for (dim in AffectDimension.entries) {
            assertTrue(
                "Dimension ${dim.key} decayRate should be > 0, got ${dim.decayRate}",
                dim.decayRate > 0.0f
            )
        }
    }

    @Test
    fun eachDimension_hasNonEmptyDescription() {
        for (dim in AffectDimension.entries) {
            assertTrue(
                "Dimension ${dim.key} should have a non-empty description",
                dim.description.isNotEmpty()
            )
        }
    }

    @Test
    fun fromKey_returnsCorrectDimension() {
        for (dim in AffectDimension.entries) {
            val found = AffectDimension.fromKey(dim.key)
            assertEquals(
                "fromKey(${dim.key}) should return ${dim.name}",
                dim, found
            )
        }
    }

    @Test
    fun fromKey_returnsNullForUnknownKey() {
        val result = AffectDimension.fromKey("nonexistent_key")
        assertEquals("fromKey for unknown key should return null", null, result)
    }

    @Test
    fun attachmentIntensity_hasHighInertia() {
        assertTrue(
            "ATTACHMENT_INTENSITY should have high inertia (>= 0.8), got ${AffectDimension.ATTACHMENT_INTENSITY.inertia}",
            AffectDimension.ATTACHMENT_INTENSITY.inertia >= 0.8f
        )
    }

    @Test
    fun noveltyResponse_hasLowInertia() {
        assertTrue(
            "NOVELTY_RESPONSE should have low inertia (<= 0.2), got ${AffectDimension.NOVELTY_RESPONSE.inertia}",
            AffectDimension.NOVELTY_RESPONSE.inertia <= 0.2f
        )
    }
}
