package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GranularConsentPluginTest {

    private lateinit var plugin: GranularConsentPlugin

    @Before
    fun setUp() {
        plugin = GranularConsentPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    // --- request command ---

    @Test
    fun request_createsPendingConsentRecord() {
        val result = plugin.execute("request|access_location|Need location for navigation")
        assertTrue("request should succeed", result.isSuccess)
        assertTrue(
            "Result should indicate pending status",
            result.data?.contains("pending") == true
        )

        // Verify via check
        val checkResult = plugin.execute("check|access_location")
        assertTrue(checkResult.isSuccess)
        assertTrue(
            "Check should show pending status",
            checkResult.data?.contains("pending") == true
        )
    }

    @Test
    fun request_includesReasonInRecord() {
        plugin.execute("request|read_contacts|Show nearby friends")
        val checkResult = plugin.execute("check|read_contacts")
        assertTrue(checkResult.isSuccess)
        assertTrue(
            "Check should include the reason",
            checkResult.data?.contains("Show nearby friends") == true
        )
    }

    // --- grant command ---

    @Test
    fun grant_changesStatusToGranted() {
        plugin.execute("request|send_sms|Send notification to user")
        val result = plugin.execute("grant|send_sms")
        assertTrue("grant should succeed", result.isSuccess)
        assertTrue(
            "Result should confirm grant",
            result.data?.contains("GRANTED") == true
        )

        // Verify
        val checkResult = plugin.execute("check|send_sms")
        assertTrue(
            "Status should be granted",
            checkResult.data?.contains("granted") == true
        )
    }

    // --- deny command ---

    @Test
    fun deny_changesStatusToDenied() {
        plugin.execute("request|track_browsing|Analytics purposes")
        val result = plugin.execute("deny|track_browsing")
        assertTrue("deny should succeed", result.isSuccess)
        assertTrue(
            "Result should confirm denial",
            result.data?.contains("DENIED") == true
        )

        val checkResult = plugin.execute("check|track_browsing")
        assertTrue(
            "Status should be denied",
            checkResult.data?.contains("denied") == true
        )
    }

    // --- check command ---

    @Test
    fun check_returnsConsentStatus() {
        plugin.execute("request|camera_access|Take photo for profile")
        plugin.execute("grant|camera_access")
        val result = plugin.execute("check|camera_access")
        assertTrue(result.isSuccess)
        assertTrue(
            "Check should return granted status",
            result.data?.contains("granted") == true
        )
    }

    @Test
    fun check_returnsNotRequestedForUnknownAction() {
        val result = plugin.execute("check|unknown_action")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate consent not requested",
            result.data?.contains("not requested") == true ||
                result.data?.contains("No consent record") == true
        )
    }

    // --- list command ---

    @Test
    fun list_showsAllConsentRecords() {
        plugin.execute("request|action_a|Reason A")
        plugin.execute("request|action_b|Reason B")
        plugin.execute("grant|action_a")

        val result = plugin.execute("list")
        assertTrue(result.isSuccess)
        assertTrue(
            "List should include action_a",
            result.data?.contains("action_a") == true
        )
        assertTrue(
            "List should include action_b",
            result.data?.contains("action_b") == true
        )
    }

    @Test
    fun list_showsEmptyMessageWhenNoRecords() {
        val result = plugin.execute("list")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate no records",
            result.data?.contains("No consent records") == true
        )
    }

    // --- revoke command ---

    @Test
    fun revoke_changesStatusToRevoked() {
        plugin.execute("request|microphone_access|Voice recording")
        plugin.execute("grant|microphone_access")

        val result = plugin.execute("revoke|microphone_access")
        assertTrue("revoke should succeed", result.isSuccess)
        assertTrue(
            "Result should confirm revocation",
            result.data?.contains("REVOKED") == true
        )

        val checkResult = plugin.execute("check|microphone_access")
        assertTrue(
            "Status should be revoked",
            checkResult.data?.contains("revoked") == true
        )
    }

    @Test
    fun revoke_failsForNonexistentAction() {
        val result = plugin.execute("revoke|nonexistent_action")
        assertFalse("revoke for nonexistent action should fail", result.isSuccess)
    }

    // --- grant without prior request ---

    @Test
    fun grant_withoutPriorRequestReturnsError() {
        val result = plugin.execute("grant|never_requested_action")
        assertFalse("grant without prior request should fail", result.isSuccess)
        assertTrue(
            "Error should mention no request found",
            result.errorMessage?.contains("No consent request found") == true
        )
    }

    @Test
    fun deny_withoutPriorRequestReturnsError() {
        val result = plugin.execute("deny|never_requested_action")
        assertFalse("deny without prior request should fail", result.isSuccess)
    }

    // --- audit command ---

    @Test
    fun audit_showsHistory() {
        plugin.execute("request|data_access|Need data")
        plugin.execute("grant|data_access")
        plugin.execute("revoke|data_access")

        val result = plugin.execute("audit")
        assertTrue(result.isSuccess)
        assertTrue(
            "Audit should show history events",
            result.data?.contains("history") == true ||
                result.data?.contains("requested") == true
        )
        assertTrue(
            "Audit should contain multiple events",
            result.data?.contains("granted") == true
        )
    }

    @Test
    fun audit_showsNoHistoryInitially() {
        val result = plugin.execute("audit")
        assertTrue(result.isSuccess)
        assertTrue(
            "Should indicate no history",
            result.data?.contains("No consent history") == true
        )
    }

    // --- Plugin properties ---

    @Test
    fun pluginId_isGranularConsent() {
        assertEquals("granular_consent", plugin.id)
    }

    @Test
    fun pluginName_isGranularConsent() {
        assertEquals("Granular Consent", plugin.name)
    }

    @Test
    fun pluginIsEnabled_defaultTrue() {
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun pluginDescription_isNotEmpty() {
        assertTrue(plugin.description.isNotEmpty())
    }
}
