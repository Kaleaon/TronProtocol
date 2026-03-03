package com.tronprotocol.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataGovernancePolicyTest {

    @Test
    fun policyFor_domainsHaveExpectedClassificationAndRetention() {
        val logPolicy = DataGovernancePolicy.policyFor(DataGovernancePolicy.DataDomain.LOGS)
        assertEquals(DataGovernancePolicy.Classification.CONFIDENTIAL, logPolicy.classification)
        assertEquals(90, logPolicy.retentionDays)
        assertTrue(logPolicy.deleteOnUserRequest)

        val memoryPolicy = DataGovernancePolicy.policyFor(DataGovernancePolicy.DataDomain.MEMORY)
        assertEquals(DataGovernancePolicy.Classification.RESTRICTED, memoryPolicy.classification)
        assertEquals(30, memoryPolicy.retentionDays)
    }

    @Test
    fun shouldDelete_respectsRetentionWindow() {
        val now = 1_000_000_000L
        val createdWithinWindow = now - (10 * DataGovernancePolicy.MILLIS_PER_DAY)
        val createdExpired = now - (120 * DataGovernancePolicy.MILLIS_PER_DAY)

        assertTrue(!DataGovernancePolicy.shouldDelete(DataGovernancePolicy.DataDomain.LOGS, createdWithinWindow, now))
        assertTrue(DataGovernancePolicy.shouldDelete(DataGovernancePolicy.DataDomain.LOGS, createdExpired, now))
    }
}
