package com.tronprotocol.app.selfmod

import org.junit.Assert.*
import org.junit.Test

class ModificationAuditRecordTest {

    // ---- creation with all fields ----

    @Test
    fun creation_allFieldsSet() {
        val timestamp = System.currentTimeMillis()
        val record = ModificationAuditRecord(
            modificationId = "mod-001",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "preflight",
            outcome = "passed",
            details = "All checks green",
            timestamp = timestamp
        )

        assertEquals("mod-001", record.modificationId)
        assertEquals(ModificationStatus.PROPOSED, record.fromStatus)
        assertEquals(ModificationStatus.PREFLIGHTED, record.toStatus)
        assertEquals("preflight", record.gate)
        assertEquals("passed", record.outcome)
        assertEquals("All checks green", record.details)
        assertEquals(timestamp, record.timestamp)
    }

    @Test
    fun creation_defaultTimestamp() {
        val before = System.currentTimeMillis()
        val record = ModificationAuditRecord(
            modificationId = "mod-002",
            fromStatus = ModificationStatus.CANARY,
            toStatus = ModificationStatus.PROMOTED,
            gate = "promotion",
            outcome = "passed",
            details = "Canary healthy"
        )
        val after = System.currentTimeMillis()

        assertTrue(record.timestamp >= before)
        assertTrue(record.timestamp <= after)
    }

    // ---- data class equality ----

    @Test
    fun equality_sameFields() {
        val timestamp = 1000L
        val record1 = ModificationAuditRecord(
            modificationId = "mod-eq",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "syntax",
            outcome = "passed",
            details = "OK",
            timestamp = timestamp
        )
        val record2 = ModificationAuditRecord(
            modificationId = "mod-eq",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "syntax",
            outcome = "passed",
            details = "OK",
            timestamp = timestamp
        )

        assertEquals(record1, record2)
        assertEquals(record1.hashCode(), record2.hashCode())
    }

    @Test
    fun equality_differentModificationId() {
        val record1 = ModificationAuditRecord(
            modificationId = "mod-a",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "syntax",
            outcome = "passed",
            details = "OK",
            timestamp = 1000L
        )
        val record2 = ModificationAuditRecord(
            modificationId = "mod-b",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "syntax",
            outcome = "passed",
            details = "OK",
            timestamp = 1000L
        )

        assertNotEquals(record1, record2)
    }

    @Test
    fun equality_differentOutcome() {
        val record1 = ModificationAuditRecord(
            modificationId = "mod-x",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.REJECTED,
            gate = "safety",
            outcome = "failed",
            details = "Unsafe pattern",
            timestamp = 2000L
        )
        val record2 = ModificationAuditRecord(
            modificationId = "mod-x",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "safety",
            outcome = "passed",
            details = "OK",
            timestamp = 2000L
        )

        assertNotEquals(record1, record2)
    }

    // ---- data class copy ----

    @Test
    fun copy_producesEqualRecord() {
        val original = ModificationAuditRecord(
            modificationId = "mod-copy",
            fromStatus = ModificationStatus.PREFLIGHTED,
            toStatus = ModificationStatus.CANARY,
            gate = "canary_deploy",
            outcome = "passed",
            details = "Deployed to canary",
            timestamp = 3000L
        )
        val copied = original.copy()

        assertEquals(original, copied)
        assertEquals(original.modificationId, copied.modificationId)
        assertEquals(original.fromStatus, copied.fromStatus)
        assertEquals(original.toStatus, copied.toStatus)
        assertEquals(original.gate, copied.gate)
        assertEquals(original.outcome, copied.outcome)
        assertEquals(original.details, copied.details)
        assertEquals(original.timestamp, copied.timestamp)
    }

    @Test
    fun copy_withModifiedField() {
        val original = ModificationAuditRecord(
            modificationId = "mod-copy2",
            fromStatus = ModificationStatus.CANARY,
            toStatus = ModificationStatus.PROMOTED,
            gate = "promotion",
            outcome = "passed",
            details = "All good",
            timestamp = 4000L
        )
        val modified = original.copy(outcome = "failed", details = "Health degraded")

        assertEquals("mod-copy2", modified.modificationId)
        assertEquals(ModificationStatus.CANARY, modified.fromStatus)
        assertEquals(ModificationStatus.PROMOTED, modified.toStatus)
        assertEquals("promotion", modified.gate)
        assertEquals("failed", modified.outcome)
        assertEquals("Health degraded", modified.details)
        assertEquals(4000L, modified.timestamp)

        assertNotEquals(original, modified)
    }

    @Test
    fun copy_originalUnchanged() {
        val original = ModificationAuditRecord(
            modificationId = "mod-immutable",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.PREFLIGHTED,
            gate = "test",
            outcome = "passed",
            details = "Original details",
            timestamp = 5000L
        )
        val _ = original.copy(details = "Changed details")

        assertEquals("Original details", original.details)
    }

    // ---- Various status transitions ----

    @Test
    fun record_capturesRollbackTransition() {
        val record = ModificationAuditRecord(
            modificationId = "mod-rb",
            fromStatus = ModificationStatus.CANARY,
            toStatus = ModificationStatus.ROLLED_BACK,
            gate = "rollback",
            outcome = "failed",
            details = "health_degradation",
            timestamp = 6000L
        )

        assertEquals(ModificationStatus.CANARY, record.fromStatus)
        assertEquals(ModificationStatus.ROLLED_BACK, record.toStatus)
        assertEquals("health_degradation", record.details)
    }

    @Test
    fun record_capturesRejectionTransition() {
        val record = ModificationAuditRecord(
            modificationId = "mod-rej",
            fromStatus = ModificationStatus.PROPOSED,
            toStatus = ModificationStatus.REJECTED,
            gate = "policy",
            outcome = "rejected",
            details = "Violates safety policy",
            timestamp = 7000L
        )

        assertEquals(ModificationStatus.PROPOSED, record.fromStatus)
        assertEquals(ModificationStatus.REJECTED, record.toStatus)
    }
}
