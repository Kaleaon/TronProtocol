package com.tronprotocol.app.selfmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CodeModificationManagerTest {

    @Test
    fun applyModification_failsWhenPreflightFails() {
        val manager = CodeModificationManager(RuntimeEnvironment.getApplication())
        val modification = manager.proposeModification(
            componentName = "Engine",
            description = "introduce syntax issue",
            originalCode = "class Engine { fun run() {} }",
            modifiedCode = "class Engine { fun run() {"
        )

        val applied = manager.applyModification(modification)

        assertFalse(applied)
        assertEquals(ModificationStatus.ROLLED_BACK, modification.status)
        assertTrue(manager.getAuditHistory().any { it.gate == "preflight" && it.outcome == "failed" })
    }

    @Test
    fun applyModification_rollsBackAutomaticallyWhenCanaryHealthDegrades() {
        val manager = CodeModificationManager(RuntimeEnvironment.getApplication())
        val modification = manager.proposeModification(
            componentName = "Planner",
            description = "safe code but degraded canary health",
            originalCode = "class Planner { fun plan() { println(\"old\") } }",
            modifiedCode = "class Planner { fun plan() { println(\"new\") } }"
        )

        val applied = manager.applyModification(
            modification,
            healthMetrics = mapOf("error_rate" to 0.4)
        )

        assertFalse(applied)
        assertEquals(ModificationStatus.ROLLED_BACK, modification.status)
        assertTrue(manager.getAuditHistory().any { it.gate == "rollback" && it.details == "health_degradation" })
    }

    @Test
    fun applyModification_promotesWhenCanaryIsHealthy() {
        val manager = CodeModificationManager(RuntimeEnvironment.getApplication())
        val modification = manager.proposeModification(
            componentName = "Responder",
            description = "safe code with healthy canary",
            originalCode = "class Responder { fun respond() { println(\"old\") } }",
            modifiedCode = "class Responder { fun respond() { println(\"new\") } }"
        )

        val applied = manager.applyModification(
            modification,
            healthMetrics = mapOf("error_rate" to 0.01, "latency_regression" to 0.05)
        )

        assertTrue(applied)
        assertEquals(ModificationStatus.PROMOTED, modification.status)
        assertTrue(manager.getAuditHistory().any { it.gate == "promotion" && it.toStatus == ModificationStatus.PROMOTED })
    }
}
