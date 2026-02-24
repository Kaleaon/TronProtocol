package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PolicyGuardrailPluginTest {

    private lateinit var plugin: PolicyGuardrailPlugin

    @Before
    fun setUp() {
        plugin = PolicyGuardrailPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    // --- evaluate (direct method) ---

    @Test
    fun evaluate_allowsNonDeniedPlugin() {
        val result = plugin.evaluate("calculator", "2+3")
        assertTrue("Non-denied plugin should be allowed", result.isSuccess)
    }

    @Test
    fun evaluate_blocksDeniedPluginAfterDenyCommand() {
        plugin.execute("deny_plugin|bad_plugin")
        val result = plugin.evaluate("bad_plugin", "some input")
        assertFalse("Denied plugin should be blocked", result.isSuccess)
        assertTrue(
            "Error should mention policy blocked",
            result.errorMessage?.contains("blocked") == true
        )
    }

    @Test
    fun allowPlugin_reEnablesPreviouslyDeniedPlugin() {
        plugin.execute("deny_plugin|notes")
        val deniedResult = plugin.evaluate("notes", "input")
        assertFalse("Plugin should be denied first", deniedResult.isSuccess)

        plugin.execute("allow_plugin|notes")
        val allowedResult = plugin.evaluate("notes", "input")
        assertTrue("Plugin should be allowed after allow_plugin", allowedResult.isSuccess)
    }

    // --- Default blocked patterns ---

    @Test
    fun defaultBlockedPatterns_includeRmRf() {
        val result = plugin.evaluate("sandbox_exec", "rm -rf /home")
        assertFalse("Input with 'rm -rf' should be blocked by default", result.isSuccess)
        assertTrue(
            "Error should mention blocked pattern",
            result.errorMessage?.contains("blocked") == true
        )
    }

    @Test
    fun defaultBlockedPatterns_includeDropTable() {
        val result = plugin.evaluate("notes", "drop table users;")
        assertFalse("Input with 'drop table' should be blocked by default", result.isSuccess)
    }

    // --- add_pattern / remove_pattern ---

    @Test
    fun addPattern_blocksMatchingInput() {
        plugin.execute("add_pattern|dangerous_keyword")
        val result = plugin.evaluate("notes", "this contains dangerous_keyword inside")
        assertFalse("Input matching added pattern should be blocked", result.isSuccess)
    }

    @Test
    fun removePattern_unblocksInput() {
        plugin.execute("add_pattern|temp_block")
        val blocked = plugin.evaluate("notes", "temp_block content")
        assertFalse("Should be blocked before removal", blocked.isSuccess)

        plugin.execute("remove_pattern|temp_block")
        val unblocked = plugin.evaluate("notes", "temp_block content")
        assertTrue("Should be unblocked after pattern removal", unblocked.isSuccess)
    }

    // --- check command ---

    @Test
    fun checkCommand_testsPluginAndInputCombination() {
        plugin.execute("deny_plugin|evil_plugin")
        val result = plugin.execute("check|evil_plugin|some input")
        assertFalse("check should reflect denied status", result.isSuccess)
    }

    @Test
    fun checkCommand_allowsNonDeniedPlugin() {
        val result = plugin.execute("check|good_plugin|normal input")
        assertTrue("check should allow non-denied plugin", result.isSuccess)
    }

    @Test
    fun checkCommand_detectsBlockedPatternInInput() {
        val result = plugin.execute("check|calculator|rm -rf everything")
        assertFalse("check should detect blocked pattern in input", result.isSuccess)
    }

    // --- list_denied ---

    @Test
    fun listDenied_returnsDeniedPlugins() {
        plugin.execute("deny_plugin|plugin_a")
        plugin.execute("deny_plugin|plugin_b")
        val result = plugin.execute("list_denied")
        assertTrue(result.isSuccess)
        assertTrue(
            "list_denied should include denied plugins",
            result.data?.contains("plugin_a") == true
        )
        assertTrue(result.data?.contains("plugin_b") == true)
    }

    @Test
    fun listDenied_emptyWhenNoDenied() {
        val result = plugin.execute("list_denied")
        assertTrue(result.isSuccess)
    }

    // --- list_patterns ---

    @Test
    fun listPatterns_showsBlockedPatterns() {
        val result = plugin.execute("list_patterns")
        assertTrue(result.isSuccess)
        assertNotNull(result.data)
    }

    // --- Empty input ---

    @Test
    fun execute_emptyInputReturnsError() {
        val result = plugin.execute("")
        assertFalse("Empty input should return error", result.isSuccess)
    }

    // --- Plugin properties ---

    @Test
    fun pluginId_isPolicyGuardrail() {
        assertEquals("policy_guardrail", plugin.id)
    }

    @Test
    fun pluginName_isPolicyGuardrail() {
        assertEquals("Policy Guardrail", plugin.name)
    }

    @Test
    fun pluginIsEnabled_defaultTrue() {
        assertTrue(plugin.isEnabled)
    }
}
