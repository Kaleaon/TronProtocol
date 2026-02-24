package com.tronprotocol.app.selfmod

import org.junit.Assert.*
import org.junit.Test

class ModificationStatusTest {

    // ---- all 6 values exist ----

    @Test
    fun proposed_exists() {
        assertNotNull(ModificationStatus.PROPOSED)
    }

    @Test
    fun preflighted_exists() {
        assertNotNull(ModificationStatus.PREFLIGHTED)
    }

    @Test
    fun canary_exists() {
        assertNotNull(ModificationStatus.CANARY)
    }

    @Test
    fun promoted_exists() {
        assertNotNull(ModificationStatus.PROMOTED)
    }

    @Test
    fun rolledBack_exists() {
        assertNotNull(ModificationStatus.ROLLED_BACK)
    }

    @Test
    fun rejected_exists() {
        assertNotNull(ModificationStatus.REJECTED)
    }

    @Test
    fun totalValueCount_isSix() {
        assertEquals(6, ModificationStatus.values().size)
    }

    // ---- values are unique ----

    @Test
    fun allValues_areUnique() {
        val values = ModificationStatus.values()
        val uniqueNames = values.map { it.name }.toSet()
        assertEquals(values.size, uniqueNames.size)
    }

    @Test
    fun allValues_haveUniqueOrdinals() {
        val values = ModificationStatus.values()
        val uniqueOrdinals = values.map { it.ordinal }.toSet()
        assertEquals(values.size, uniqueOrdinals.size)
    }

    // ---- valueOf works for all values ----

    @Test
    fun valueOf_proposed() {
        assertEquals(ModificationStatus.PROPOSED, ModificationStatus.valueOf("PROPOSED"))
    }

    @Test
    fun valueOf_preflighted() {
        assertEquals(ModificationStatus.PREFLIGHTED, ModificationStatus.valueOf("PREFLIGHTED"))
    }

    @Test
    fun valueOf_canary() {
        assertEquals(ModificationStatus.CANARY, ModificationStatus.valueOf("CANARY"))
    }

    @Test
    fun valueOf_promoted() {
        assertEquals(ModificationStatus.PROMOTED, ModificationStatus.valueOf("PROMOTED"))
    }

    @Test
    fun valueOf_rolledBack() {
        assertEquals(ModificationStatus.ROLLED_BACK, ModificationStatus.valueOf("ROLLED_BACK"))
    }

    @Test
    fun valueOf_rejected() {
        assertEquals(ModificationStatus.REJECTED, ModificationStatus.valueOf("REJECTED"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun valueOf_invalidName_throwsException() {
        ModificationStatus.valueOf("NONEXISTENT")
    }

    // ---- Ordinal ordering ----

    @Test
    fun ordinal_proposedIsFirst() {
        assertEquals(0, ModificationStatus.PROPOSED.ordinal)
    }

    @Test
    fun ordinal_rejectedIsLast() {
        assertEquals(5, ModificationStatus.REJECTED.ordinal)
    }
}
