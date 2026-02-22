package com.tronprotocol.app.phylactery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryTierTest {

    @Test
    fun testAllTiersPresent() {
        assertEquals(4, MemoryTier.entries.size)
    }

    @Test
    fun testTierLabels() {
        assertEquals("working", MemoryTier.WORKING.label)
        assertEquals("episodic", MemoryTier.EPISODIC.label)
        assertEquals("semantic", MemoryTier.SEMANTIC.label)
        assertEquals("core_identity", MemoryTier.CORE_IDENTITY.label)
    }

    @Test
    fun testFromLabelValid() {
        assertNotNull(MemoryTier.fromLabel("working"))
        assertEquals(MemoryTier.WORKING, MemoryTier.fromLabel("working"))
        assertEquals(MemoryTier.EPISODIC, MemoryTier.fromLabel("episodic"))
        assertEquals(MemoryTier.SEMANTIC, MemoryTier.fromLabel("semantic"))
        assertEquals(MemoryTier.CORE_IDENTITY, MemoryTier.fromLabel("core_identity"))
    }

    @Test
    fun testFromLabelInvalid() {
        assertNull(MemoryTier.fromLabel("nonexistent"))
        assertNull(MemoryTier.fromLabel(""))
    }
}
