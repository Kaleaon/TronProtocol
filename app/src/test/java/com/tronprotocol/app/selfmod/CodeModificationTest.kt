package com.tronprotocol.app.selfmod

import org.junit.Assert.*
import org.junit.Test

class CodeModificationTest {

    private fun createModification(
        id: String = "mod-001",
        status: ModificationStatus = ModificationStatus.PROPOSED
    ): CodeModification {
        return CodeModification(
            id = id,
            componentName = "Engine",
            description = "Optimize loop performance",
            originalCode = "for (i in 0..n) { process(i) }",
            modifiedCode = "items.forEach { process(it) }",
            timestamp = System.currentTimeMillis(),
            status = status
        )
    }

    // ---- creation with all fields ----

    @Test
    fun creation_allFieldsSet() {
        val timestamp = System.currentTimeMillis()
        val mod = CodeModification(
            id = "mod-100",
            componentName = "Parser",
            description = "Refactor parsing logic",
            originalCode = "val x = parse(input)",
            modifiedCode = "val x = betterParse(input)",
            timestamp = timestamp,
            status = ModificationStatus.PROPOSED
        )

        assertEquals("mod-100", mod.id)
        assertEquals("Parser", mod.componentName)
        assertEquals("Refactor parsing logic", mod.description)
        assertEquals("val x = parse(input)", mod.originalCode)
        assertEquals("val x = betterParse(input)", mod.modifiedCode)
        assertEquals(timestamp, mod.timestamp)
        assertEquals(ModificationStatus.PROPOSED, mod.status)
    }

    // ---- default status is PROPOSED ----

    @Test
    fun defaultStatus_isProposed() {
        val mod = createModification()
        assertEquals(ModificationStatus.PROPOSED, mod.status)
    }

    // ---- status can be changed ----

    @Test
    fun status_canBeChangedToPreflighted() {
        val mod = createModification()
        mod.status = ModificationStatus.PREFLIGHTED
        assertEquals(ModificationStatus.PREFLIGHTED, mod.status)
    }

    @Test
    fun status_canBeChangedToCanary() {
        val mod = createModification()
        mod.status = ModificationStatus.CANARY
        assertEquals(ModificationStatus.CANARY, mod.status)
    }

    @Test
    fun status_canBeChangedToPromoted() {
        val mod = createModification()
        mod.status = ModificationStatus.PROMOTED
        assertEquals(ModificationStatus.PROMOTED, mod.status)
    }

    @Test
    fun status_canBeChangedToRolledBack() {
        val mod = createModification()
        mod.status = ModificationStatus.ROLLED_BACK
        assertEquals(ModificationStatus.ROLLED_BACK, mod.status)
    }

    @Test
    fun status_canBeChangedToRejected() {
        val mod = createModification()
        mod.status = ModificationStatus.REJECTED
        assertEquals(ModificationStatus.REJECTED, mod.status)
    }

    @Test
    fun status_canTransitionThroughLifecycle() {
        val mod = createModification()
        assertEquals(ModificationStatus.PROPOSED, mod.status)

        mod.status = ModificationStatus.PREFLIGHTED
        assertEquals(ModificationStatus.PREFLIGHTED, mod.status)

        mod.status = ModificationStatus.CANARY
        assertEquals(ModificationStatus.CANARY, mod.status)

        mod.status = ModificationStatus.PROMOTED
        assertEquals(ModificationStatus.PROMOTED, mod.status)
    }

    // ---- id is unique (create two, compare) ----

    @Test
    fun id_twoModificationsHaveDifferentIds() {
        val mod1 = createModification(id = "mod-001")
        val mod2 = createModification(id = "mod-002")
        assertNotEquals(mod1.id, mod2.id)
    }

    @Test
    fun id_sameIdMeansLogicallySameModification() {
        val mod1 = createModification(id = "mod-same")
        val mod2 = createModification(id = "mod-same")
        assertEquals(mod1.id, mod2.id)
    }

    // ---- backup stores original code ----

    @Test
    fun backup_originalCodePreserved() {
        val originalCode = "fun oldMethod() { /* old implementation */ }"
        val mod = CodeModification(
            id = "mod-backup",
            componentName = "Module",
            description = "Update method",
            originalCode = originalCode,
            modifiedCode = "fun newMethod() { /* new implementation */ }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        assertEquals(originalCode, mod.originalCode)
    }

    @Test
    fun backup_originalCodeDiffersFromModified() {
        val mod = createModification()
        assertNotEquals(mod.originalCode, mod.modifiedCode)
    }

    // ---- Additional properties ----

    @Test
    fun appliedTimestamp_defaultsToZero() {
        val mod = createModification()
        assertEquals(0L, mod.appliedTimestamp)
    }

    @Test
    fun appliedTimestamp_canBeSet() {
        val mod = createModification()
        val time = System.currentTimeMillis()
        mod.appliedTimestamp = time
        assertEquals(time, mod.appliedTimestamp)
    }

    @Test
    fun backupId_defaultsToNull() {
        val mod = createModification()
        assertNull(mod.backupId)
    }

    @Test
    fun backupId_canBeSet() {
        val mod = createModification()
        mod.backupId = "backup-123"
        assertEquals("backup-123", mod.backupId)
    }

    @Test
    fun rollbackCheckpointId_defaultsToNull() {
        val mod = createModification()
        assertNull(mod.rollbackCheckpointId)
    }

    @Test
    fun rollbackCheckpointId_canBeSet() {
        val mod = createModification()
        mod.rollbackCheckpointId = "checkpoint-456"
        assertEquals("checkpoint-456", mod.rollbackCheckpointId)
    }

    // ---- toString ----

    @Test
    fun toString_containsId() {
        val mod = createModification(id = "mod-tostring")
        assertTrue(mod.toString().contains("mod-tostring"))
    }

    @Test
    fun toString_containsComponentName() {
        val mod = createModification()
        assertTrue(mod.toString().contains("Engine"))
    }

    @Test
    fun toString_containsStatus() {
        val mod = createModification()
        assertTrue(mod.toString().contains("PROPOSED"))
    }
}
