package com.tronprotocol.app.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutonomyPolicyTest {

    @Test
    fun evaluate_allowsByDefault_whenFreedomEnabled() {
        val policy = RuntimeAutonomyPolicy()
        val decision = policy.evaluate("calculator")
        assertTrue(decision.allowed)
        assertTrue(decision.reason.contains("freedom-of-choice"))
    }

    @Test
    fun evaluate_blocksSensitivePlugin_whenTampered() {
        val policy = RuntimeAutonomyPolicy()
        policy.reportIntegritySignal("file_manager", trusted = false)
        val decision = policy.evaluate("file_manager")
        assertFalse(decision.allowed)
        assertTrue(decision.requiresSelfCheck)
    }

    @Test
    fun selfCheck_okAfterClearingTamperSignal() {
        val policy = RuntimeAutonomyPolicy()
        policy.reportIntegritySignal("sandbox_exec", trusted = false)
        policy.reportIntegritySignal("sandbox_exec", trusted = true)
        val status = policy.runSelfCheck(setOf("sandbox_exec"))
        assertTrue(status.contains("Self-check OK"))
    }
}
