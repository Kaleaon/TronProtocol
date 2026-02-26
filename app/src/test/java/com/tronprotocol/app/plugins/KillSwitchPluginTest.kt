package com.tronprotocol.app.plugins

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KillSwitchPluginTest {

    private lateinit var plugin: KillSwitchPlugin

    @Before
    fun setUp() {
        plugin = KillSwitchPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    // --- Initial state ---

    @Test
    fun initialState_isNotEngaged() {
        val result = plugin.execute("check")
        assertTrue(result.isSuccess)
        val json = JSONObject(result.data!!)
        assertFalse("Initial state should not be engaged", json.getBoolean("engaged"))
    }

    // --- Engage ---

    @Test
    fun engage_setsEngagedStateWithReason() {
        val result = plugin.execute("engage|Critical security threat detected")
        assertTrue(result.isSuccess)
        assertTrue(
            "Result should confirm engagement",
            result.data?.contains("ENGAGED") == true
        )

        // Verify via check
        val checkResult = plugin.execute("check")
        val json = JSONObject(checkResult.data!!)
        assertTrue("Should be engaged after engage command", json.getBoolean("engaged"))
        assertEquals(
            "Critical security threat detected",
            json.getString("reason")
        )
    }

    @Test
    fun engage_requiresReason() {
        val result = plugin.execute("engage|")
        assertFalse("engage without reason should fail", result.isSuccess)
    }

    @Test
    fun engage_requiresReasonPart() {
        val result = plugin.execute("engage")
        // "engage" without a pipe separator has parts.size < 2, but the split still gives 1 part
        // Actually with split limit 2, "engage" gives ["engage"], so parts.size < 2
        assertFalse("engage without pipe and reason should fail", result.isSuccess)
    }

    // --- Disengage ---

    @Test
    fun disengage_requiresCorrectAuthCode() {
        plugin.execute("engage|Test reason")

        val result = plugin.execute("disengage|TRON_OVERRIDE")
        assertTrue("Disengage with correct default auth code should succeed", result.isSuccess)
        assertTrue(result.data?.contains("DISENGAGED") == true)

        // Verify disengaged
        val checkResult = plugin.execute("check")
        val json = JSONObject(checkResult.data!!)
        assertFalse("Should not be engaged after disengage", json.getBoolean("engaged"))
    }

    @Test
    fun disengage_withWrongCodeFails() {
        plugin.execute("engage|Test reason")

        val result = plugin.execute("disengage|WRONG_CODE")
        assertFalse("Disengage with wrong auth code should fail", result.isSuccess)
        assertTrue(
            "Error should mention invalid auth code",
            result.errorMessage?.contains("Invalid auth code") == true
        )

        // Verify still engaged
        val checkResult = plugin.execute("check")
        val json = JSONObject(checkResult.data!!)
        assertTrue("Should remain engaged after failed disengage", json.getBoolean("engaged"))
    }

    @Test
    fun disengage_resetsEngagedState() {
        plugin.execute("engage|Emergency")
        plugin.execute("disengage|TRON_OVERRIDE")

        val checkResult = plugin.execute("check")
        val json = JSONObject(checkResult.data!!)
        assertFalse("Engaged should be false after disengage", json.getBoolean("engaged"))
        assertFalse("Reason should not be present when disengaged", json.has("reason"))
    }

    // --- Check command ---

    @Test
    fun check_returnsJsonWithEngagedStatus() {
        val result = plugin.execute("check")
        assertTrue(result.isSuccess)
        val json = JSONObject(result.data!!)
        assertTrue("JSON should have 'engaged' field", json.has("engaged"))
    }

    @Test
    fun check_includesReasonWhenEngaged() {
        plugin.execute("engage|My reason")
        val result = plugin.execute("check")
        val json = JSONObject(result.data!!)
        assertTrue(json.getBoolean("engaged"))
        assertEquals("My reason", json.getString("reason"))
    }

    // --- set_auth command ---

    @Test
    fun setAuth_changesAuthCode() {
        val setResult = plugin.execute("set_auth|NEW_SECRET_CODE")
        assertTrue("set_auth should succeed", setResult.isSuccess)

        // Engage and try disengage with new code
        plugin.execute("engage|Test")
        val oldCodeResult = plugin.execute("disengage|TRON_OVERRIDE")
        assertFalse("Old auth code should no longer work", oldCodeResult.isSuccess)

        val newCodeResult = plugin.execute("disengage|NEW_SECRET_CODE")
        assertTrue("New auth code should work", newCodeResult.isSuccess)
    }

    @Test
    fun setAuth_rejectsShortCodes() {
        val result = plugin.execute("set_auth|abc")
        assertFalse("Auth code shorter than 4 chars should be rejected", result.isSuccess)
        assertTrue(
            "Error should mention minimum length",
            result.errorMessage?.contains("4") == true
        )
    }

    @Test
    fun setAuth_acceptsFourCharCode() {
        val result = plugin.execute("set_auth|abcd")
        assertTrue("4-char auth code should be accepted", result.isSuccess)
    }

    // --- History ---

    @Test
    fun history_recordsEngageDisengageEvents() {
        plugin.execute("engage|First engage")
        plugin.execute("disengage|TRON_OVERRIDE")
        plugin.execute("engage|Second engage")

        val result = plugin.execute("history")
        assertTrue(result.isSuccess)
        assertTrue(
            "History should contain engage events",
            result.data?.contains("engaged") == true
        )
        assertTrue(
            "History should contain disengage events",
            result.data?.contains("disengaged") == true
        )
    }

    @Test
    fun history_emptyInitially() {
        val result = plugin.execute("history")
        assertTrue(result.isSuccess)
        assertTrue(
            "Initial history should show no entries",
            result.data?.contains("No kill switch history") == true
        )
    }

    // --- auto_trigger ---

    @Test
    fun autoTrigger_registersCondition() {
        val result = plugin.execute("auto_trigger|battery_below_5_percent")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should confirm condition registration",
            result.data?.contains("battery_below_5_percent") == true
        )
    }

    @Test
    fun autoTrigger_duplicateConditionHandled() {
        plugin.execute("auto_trigger|memory_exhausted")
        val result = plugin.execute("auto_trigger|memory_exhausted")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate already registered",
            result.data?.contains("already registered") == true
        )
    }

    // --- Status ---

    @Test
    fun status_showsCorrectStateWhenNotEngaged() {
        val result = plugin.execute("status")
        assertTrue(result.isSuccess)
        assertTrue(
            "Status should show Engaged: false",
            result.data?.contains("Engaged: false") == true
        )
    }

    @Test
    fun status_showsCorrectStateWhenEngaged() {
        plugin.execute("engage|Testing status")
        val result = plugin.execute("status")
        assertTrue(result.isSuccess)
        assertTrue(
            "Status should show Engaged: true",
            result.data?.contains("Engaged: true") == true
        )
        assertTrue(
            "Status should show reason",
            result.data?.contains("Testing status") == true
        )
    }

    // --- Double engage ---

    @Test
    fun doubleEngage_returnsAlreadyEngagedMessage() {
        plugin.execute("engage|First reason")
        val result = plugin.execute("engage|Second reason")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate already engaged",
            result.data?.contains("already engaged") == true
        )
    }

    // --- Plugin properties ---

    @Test
    fun pluginId_isKillSwitch() {
        assertEquals("kill_switch", plugin.id)
    }

    @Test
    fun pluginName_isKillSwitch() {
        assertEquals("Kill Switch", plugin.name)
    }

    @Test
    fun pluginIsEnabled_defaultTrue() {
        assertTrue(plugin.isEnabled)
    }
}
