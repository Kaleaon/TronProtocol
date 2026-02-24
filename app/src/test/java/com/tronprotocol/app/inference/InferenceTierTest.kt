package com.tronprotocol.app.inference

import org.junit.Assert.*
import org.junit.Test

class InferenceTierTest {

    @Test
    fun allThreeTiersExist() {
        val tiers = InferenceTier.entries
        assertEquals("There should be exactly 3 inference tiers", 3, tiers.size)

        assertNotNull("LOCAL_ALWAYS_ON should exist", InferenceTier.LOCAL_ALWAYS_ON)
        assertNotNull("LOCAL_ON_DEMAND should exist", InferenceTier.LOCAL_ON_DEMAND)
        assertNotNull("CLOUD_FALLBACK should exist", InferenceTier.CLOUD_FALLBACK)
    }

    @Test
    fun tiersHaveCorrectPriorities() {
        assertEquals(
            "LOCAL_ALWAYS_ON should have priority 1",
            1, InferenceTier.LOCAL_ALWAYS_ON.priority
        )
        assertEquals(
            "LOCAL_ON_DEMAND should have priority 2",
            2, InferenceTier.LOCAL_ON_DEMAND.priority
        )
        assertEquals(
            "CLOUD_FALLBACK should have priority 3",
            3, InferenceTier.CLOUD_FALLBACK.priority
        )
    }

    @Test
    fun valuesAreUnique() {
        val priorities = InferenceTier.entries.map { it.priority }
        assertEquals(
            "All tier priorities should be unique",
            priorities.size,
            priorities.toSet().size
        )

        val labels = InferenceTier.entries.map { it.label }
        assertEquals(
            "All tier labels should be unique",
            labels.size,
            labels.toSet().size
        )
    }
}
