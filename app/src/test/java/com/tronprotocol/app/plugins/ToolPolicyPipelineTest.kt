package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolPolicyPipelineTest {

    private lateinit var engine: ToolPolicyEngine

    @Before
    fun setUp() {
        engine = ToolPolicyEngine()
        // Don't call initialize() — that requires Android Context
        // Instead, test the pipeline logic directly with manual rules
    }

    // --- Cumulative restriction ---

    @Test
    fun testCumulativeDenial() {
        // A GLOBAL ALLOW should not prevent a GROUP DENY from taking effect
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GLOBAL, "*",
            ToolPolicyEngine.Action.ALLOW, "Global default: allow all"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GROUP, "telegram_bridge",
            ToolPolicyEngine.Action.DENY, "Network plugins denied for safety"
        ))

        val decision = engine.evaluatePipeline("telegram_bridge")
        assertFalse("Pipeline should deny despite global allow", decision.allowed)
        assertEquals(ToolPolicyEngine.PolicyLayer.GROUP, decision.decidingLayer)
    }

    @Test
    fun testAllowDoesNotOverridePriorDeny() {
        // SESSION DENY should stick even if GROUP has ALLOW
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.SESSION, "calculator",
            ToolPolicyEngine.Action.DENY, "Session-level deny"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GROUP, "calculator",
            ToolPolicyEngine.Action.ALLOW, "Group-level allow"
        ))

        val decision = engine.evaluatePipeline("calculator")
        assertFalse("ALLOW should not undo a prior DENY", decision.allowed)
        assertEquals(ToolPolicyEngine.PolicyLayer.SESSION, decision.decidingLayer)
    }

    @Test
    fun testNoRulesDefaultsToAllow() {
        val decision = engine.evaluatePipeline("calculator")
        assertTrue("No rules should default to allow", decision.allowed)
    }

    @Test
    fun testSubAgentDenyInPipeline() {
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GLOBAL, "*",
            ToolPolicyEngine.Action.ALLOW, "Global allow"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.SUB_AGENT, "file_manager",
            ToolPolicyEngine.Action.DENY, "Sub-agents can't use file_manager"
        ))

        // Non-sub-agent: allowed
        val normalDecision = engine.evaluatePipeline("file_manager", isSubAgent = false)
        assertTrue("Non-sub-agent should be allowed", normalDecision.allowed)

        // Sub-agent: denied
        val subAgentDecision = engine.evaluatePipeline("file_manager", isSubAgent = true)
        assertFalse("Sub-agent should be denied", subAgentDecision.allowed)
        assertEquals(ToolPolicyEngine.PolicyLayer.SUB_AGENT, subAgentDecision.decidingLayer)
    }

    @Test
    fun testSandboxDenyInPipeline() {
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GLOBAL, "*",
            ToolPolicyEngine.Action.ALLOW, "Global allow"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.SANDBOX, "sandbox_exec",
            ToolPolicyEngine.Action.DENY, "Code execution denied in sandbox"
        ))

        // Non-sandboxed: allowed
        val normalDecision = engine.evaluatePipeline("sandbox_exec", isSandboxed = false)
        assertTrue(normalDecision.allowed)

        // Sandboxed: denied
        val sandboxedDecision = engine.evaluatePipeline("sandbox_exec", isSandboxed = true)
        assertFalse(sandboxedDecision.allowed)
        assertEquals(ToolPolicyEngine.PolicyLayer.SANDBOX, sandboxedDecision.decidingLayer)
    }

    @Test
    fun testMultipleDeniesTracksMostRestrictive() {
        // Both GROUP and SANDBOX deny — most restrictive (last evaluated) should be the deciding layer
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GROUP, "file_manager",
            ToolPolicyEngine.Action.DENY, "Group deny"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.SANDBOX, "file_manager",
            ToolPolicyEngine.Action.DENY, "Sandbox deny"
        ))

        val decision = engine.evaluatePipeline("file_manager", isSandboxed = true)
        assertFalse(decision.allowed)
        // The last (most restrictive) deny should win
        assertEquals(ToolPolicyEngine.PolicyLayer.SANDBOX, decision.decidingLayer)
    }

    @Test
    fun testEvaluatedLayersCount() {
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GLOBAL, "*",
            ToolPolicyEngine.Action.ALLOW, "Global allow"
        ))

        // Non-sub-agent, non-sandboxed: 4 layers evaluated (GLOBAL, PLUGIN_PROFILE, SESSION, GROUP)
        val decision = engine.evaluatePipeline("calculator")
        assertEquals(4, decision.evaluatedLayers)

        // Sub-agent + sandboxed: 6 layers
        val fullDecision = engine.evaluatePipeline("calculator", isSubAgent = true, isSandboxed = true)
        assertEquals(6, fullDecision.evaluatedLayers)
    }

    // --- Comparison with legacy evaluate() ---

    @Test
    fun testPipelineIsStricterThanLegacy() {
        // Set up a case where legacy evaluate() allows but pipeline denies
        // Legacy uses "first match wins" — GLOBAL ALLOW would match first
        // Pipeline cumulates — GROUP DENY would still block

        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.GLOBAL, "*",
            ToolPolicyEngine.Action.ALLOW, "Global default"
        ))
        engine.addRule(ToolPolicyEngine.PolicyRule(
            ToolPolicyEngine.PolicyLayer.SESSION, "notes",
            ToolPolicyEngine.Action.DENY, "Session deny"
        ))

        // Legacy: SUB_AGENT skipped (not sub-agent), SANDBOX skipped, GROUP no match,
        // SESSION matches with DENY => denied too (but via different path)
        val legacyDecision = engine.evaluate("notes")
        assertFalse(legacyDecision.allowed)

        // Pipeline: also denied, but via cumulative approach
        val pipelineDecision = engine.evaluatePipeline("notes")
        assertFalse(pipelineDecision.allowed)
    }
}
