package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Test

class MemoryStageTest {

    @Test
    fun allFourStagesExist() {
        val stages = MemoryStage.values()
        assertEquals("There should be exactly 4 memory stages", 4, stages.size)

        assertNotNull("SENSORY should exist", MemoryStage.SENSORY)
        assertNotNull("WORKING should exist", MemoryStage.WORKING)
        assertNotNull("EPISODIC should exist", MemoryStage.EPISODIC)
        assertNotNull("SEMANTIC should exist", MemoryStage.SEMANTIC)
    }

    @Test
    fun durabilityWeight_increasesFromSensoryToSemantic() {
        assertTrue(
            "SENSORY durability should be less than WORKING",
            MemoryStage.SENSORY.durabilityWeight < MemoryStage.WORKING.durabilityWeight
        )
        assertTrue(
            "WORKING durability should be less than EPISODIC",
            MemoryStage.WORKING.durabilityWeight < MemoryStage.EPISODIC.durabilityWeight
        )
        assertTrue(
            "EPISODIC durability should be less than SEMANTIC",
            MemoryStage.EPISODIC.durabilityWeight < MemoryStage.SEMANTIC.durabilityWeight
        )
    }

    @Test
    fun sensory_hasLowestDurability_semantic_hasHighest() {
        val stages = MemoryStage.values()
        val minDurability = stages.minByOrNull { it.durabilityWeight }
        val maxDurability = stages.maxByOrNull { it.durabilityWeight }

        assertEquals(
            "SENSORY should have the lowest durability weight",
            MemoryStage.SENSORY,
            minDurability
        )
        assertEquals(
            "SEMANTIC should have the highest durability weight",
            MemoryStage.SEMANTIC,
            maxDurability
        )
    }

    @Test
    fun defaultTtlMinutes_valuesArePositive() {
        for (stage in MemoryStage.values()) {
            assertTrue(
                "defaultTtlMinutes for ${stage.name} should be positive, got ${stage.defaultTtlMinutes}",
                stage.defaultTtlMinutes > 0
            )
        }
    }

    @Test
    fun semantic_hasLongestTTL() {
        val stages = MemoryStage.values()
        val maxTtl = stages.maxByOrNull { it.defaultTtlMinutes }

        assertEquals(
            "SEMANTIC should have the longest TTL",
            MemoryStage.SEMANTIC,
            maxTtl
        )
    }
}
