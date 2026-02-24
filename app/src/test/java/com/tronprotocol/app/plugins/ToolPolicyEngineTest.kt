package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ToolPolicyEngineTest {

    private lateinit var engine: ToolPolicyEngine

    @Before
    fun setUp() {
        engine = ToolPolicyEngine()
        engine.initialize(RuntimeEnvironment.getApplication())
    }

    // --- Basic evaluation ---

    @Test
    fun evaluate_allowsKnownSafePlugins() {
        // calculator is in group:safe which is ALLOWed for sub-agents,
        // and the global default is ALLOW
        val decision = engine.evaluate("calculator")
        assertTrue("Safe plugin 'calculator' should be allowed", decision.allowed)
    }

    @Test
    fun evaluate_allowsDatetimePlugin() {
        val decision = engine.evaluate("datetime")
        assertTrue("datetime plugin should be allowed by default", decision.allowed)
    }

    @Test
    fun evaluate_allowsTextAnalysisPlugin() {
        val decision = engine.evaluate("text_analysis")
        assertTrue("text_analysis plugin should be allowed by default", decision.allowed)
    }

    // --- Blocking via rules ---

    @Test
    fun evaluate_blockedCapabilitiesDenyExecution() {
        // Sub-agent mode denies network plugins by default
        val decision = engine.evaluate("web_search", isSubAgent = true)
        assertFalse(
            "Network plugin should be denied for sub-agents",
            decision.allowed
        )
        assertEquals(
            "Deciding layer should be SUB_AGENT",
            ToolPolicyEngine.PolicyLayer.SUB_AGENT,
            decision.decidingLayer
        )
    }

    @Test
    fun addRule_blocksMatchingPlugins() {
        // Add a PLUGIN_PROFILE deny rule for a specific plugin
        engine.addRule(
            ToolPolicyEngine.PolicyRule(
                layer = ToolPolicyEngine.PolicyLayer.PLUGIN_PROFILE,
                pluginId = "notes",
                action = ToolPolicyEngine.Action.DENY,
                reason = "Notes plugin blocked for test"
            )
        )
        val decision = engine.evaluate("notes")
        assertFalse("Notes should be blocked after adding deny rule", decision.allowed)
        assertEquals(
            ToolPolicyEngine.PolicyLayer.PLUGIN_PROFILE,
            decision.decidingLayer
        )
        assertTrue(
            "Reason should mention the block reason",
            decision.reason.contains("Notes plugin blocked for test")
        )
    }

    @Test
    fun removeRule_unblocksPlugins() {
        // First block the plugin
        engine.addRule(
            ToolPolicyEngine.PolicyRule(
                layer = ToolPolicyEngine.PolicyLayer.SESSION,
                pluginId = "calculator",
                action = ToolPolicyEngine.Action.DENY,
                reason = "Temporarily blocked"
            )
        )
        val blocked = engine.evaluate("calculator")
        assertFalse("Calculator should be blocked", blocked.allowed)

        // Now remove the rule
        val removed = engine.removeRule(
            ToolPolicyEngine.PolicyLayer.SESSION,
            "calculator"
        )
        assertTrue("removeRule should return true", removed)

        val unblocked = engine.evaluate("calculator")
        assertTrue("Calculator should be allowed after removing deny rule", unblocked.allowed)
    }

    // --- Capability-based evaluation ---

    @Test
    fun grantedCapabilities_allowAccess() {
        engine.setGrantedCapabilities(
            "test_plugin",
            setOf(Capability.NETWORK_OUTBOUND, Capability.FILESYSTEM_READ)
        )
        val decision = engine.evaluateCapabilities(
            "test_plugin",
            setOf(Capability.NETWORK_OUTBOUND)
        )
        assertTrue("Should allow when required capability is granted", decision.allowed)
        assertTrue("No missing capabilities", decision.missingCapabilities.isEmpty())
    }

    @Test
    fun missingCapabilities_denyAccess() {
        engine.setGrantedCapabilities("test_plugin", setOf(Capability.FILESYSTEM_READ))
        val decision = engine.evaluateCapabilities(
            "test_plugin",
            setOf(Capability.NETWORK_OUTBOUND, Capability.FILESYSTEM_READ)
        )
        assertFalse("Should deny when a required capability is missing", decision.allowed)
        assertTrue(
            "Missing capabilities should include NETWORK_OUTBOUND",
            decision.missingCapabilities.contains(Capability.NETWORK_OUTBOUND)
        )
    }

    @Test
    fun getGrantedCapabilities_returnsSetCapabilities() {
        val caps = setOf(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE)
        engine.setGrantedCapabilities("my_plugin", caps)
        val retrieved = engine.getGrantedCapabilities("my_plugin")
        assertEquals("Should return the same capabilities that were set", caps, retrieved)
    }

    @Test
    fun getGrantedCapabilities_returnsEmptyForUnknownPlugin() {
        val retrieved = engine.getGrantedCapabilities("unknown_plugin")
        assertTrue("Should return empty set for unknown plugin", retrieved.isEmpty())
    }

    @Test
    fun evaluateCapabilities_allowsWhenNoCapabilitiesRequired() {
        // If no capabilities are required, should always allow
        val decision = engine.evaluateCapabilities("any_plugin", emptySet())
        assertTrue("Should allow when no capabilities are required", decision.allowed)
    }

    // --- Sandbox restrictions ---

    @Test
    fun evaluate_sandboxModeDeniesFileManager() {
        val decision = engine.evaluate("file_manager", isSandboxed = true)
        assertFalse("file_manager should be denied in sandbox mode", decision.allowed)
        assertEquals(
            ToolPolicyEngine.PolicyLayer.SANDBOX,
            decision.decidingLayer
        )
    }

    @Test
    fun evaluate_sandboxModeDeniesCodeExecution() {
        val decision = engine.evaluate("sandbox_exec", isSandboxed = true)
        assertFalse("sandbox_exec should be denied in sandbox mode", decision.allowed)
    }

    @Test
    fun evaluate_sandboxModeDeniesExternalMessaging() {
        val decision = engine.evaluate("telegram_bridge", isSandboxed = true)
        assertFalse("telegram_bridge should be denied in sandbox mode", decision.allowed)
    }

    // --- Sub-agent restrictions ---

    @Test
    fun evaluate_subAgentDeniesSystemPlugins() {
        val decision = engine.evaluate("file_manager", isSubAgent = true)
        assertFalse("System plugins should be denied for sub-agents", decision.allowed)
    }

    @Test
    fun evaluate_subAgentAllowsSafePlugins() {
        val decision = engine.evaluate("calculator", isSubAgent = true)
        assertTrue("Safe plugins should be allowed for sub-agents", decision.allowed)
    }

    // --- Default policy ---

    @Test
    fun evaluate_defaultGlobalPolicyAllows() {
        // A custom plugin not in any group should be allowed by global default
        val decision = engine.evaluate("custom_new_plugin")
        assertTrue("Unknown plugins should be allowed by global default", decision.allowed)
    }

    @Test
    fun evaluate_returnsNonEmptyReason() {
        val decision = engine.evaluate("calculator")
        assertTrue("Decision reason should not be empty", decision.reason.isNotEmpty())
    }

    @Test
    fun evaluate_tracksEvaluatedLayerCount() {
        val decision = engine.evaluate("calculator")
        assertTrue("At least one layer should be evaluated", decision.evaluatedLayers > 0)
    }
}
