package com.tronprotocol.app.hedonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyZoneTest {

    @Test
    fun testAllZonesPresent() {
        assertEquals(15, BodyZone.entries.size)
    }

    @Test
    fun testPrimaryErogenousHasHighestWeight() {
        val primary = BodyZone.PRIMARY_EROGENOUS
        assertEquals(0.95f, primary.baseHedonicWeight, 0.001f)
        assertTrue(primary.isErogenous)
    }

    @Test
    fun testSecondaryErogenousWeight() {
        val secondary = BodyZone.SECONDARY_EROGENOUS
        assertEquals(0.85f, secondary.baseHedonicWeight, 0.001f)
        assertTrue(secondary.isErogenous)
    }

    @Test
    fun testNonErogenousZones() {
        val nonErogenous = BodyZone.entries.filter { !it.isErogenous }
        assertEquals(13, nonErogenous.size)
        nonErogenous.forEach { assertFalse(it.isErogenous) }
    }

    @Test
    fun testWeightsInRange() {
        for (zone in BodyZone.entries) {
            assertTrue("${zone.label} weight should be >= 0", zone.baseHedonicWeight >= 0.0f)
            assertTrue("${zone.label} weight should be <= 1", zone.baseHedonicWeight <= 1.0f)
        }
    }

    @Test
    fun testFromLabelValid() {
        val zone = BodyZone.fromLabel("inner_ears")
        assertNotNull(zone)
        assertEquals(BodyZone.INNER_EARS, zone)
    }

    @Test
    fun testFromLabelInvalid() {
        assertNull(BodyZone.fromLabel("nonexistent_zone"))
    }

    @Test
    fun testLowestWeightZone() {
        val lowest = BodyZone.entries.minBy { it.baseHedonicWeight }
        assertEquals(BodyZone.LOWER_LEGS_FEET, lowest)
        assertEquals(0.2f, lowest.baseHedonicWeight, 0.001f)
    }

    @Test
    fun testInnerEarsHighSensitivity() {
        assertEquals(0.75f, BodyZone.INNER_EARS.baseHedonicWeight, 0.001f)
    }
}
