package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SMSSendPluginTest {

    private lateinit var context: Context
    private lateinit var plugin: SMSSendPlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        plugin = SMSSendPlugin()
        plugin.initialize(context)
    }

    @Test
    fun sendWithoutAllowedNumber_returnsError() {
        val result = plugin.execute("send|+15551234567|Hello there")
        assertFalse("Send to non-allowed number should fail", result.isSuccess)
        assertTrue(
            "Error should mention allow list",
            result.errorMessage?.contains("allow list") == true ||
                    result.errorMessage?.contains("not in allow") == true
        )
    }

    @Test
    fun allow_addsNumberToAllowList() {
        val result = plugin.execute("allow|+15551234567")
        assertTrue("Allow command should succeed", result.isSuccess)
        assertTrue(
            "Success message should mention the number or allow list",
            result.data?.contains("+15551234567") == true ||
                    result.data?.contains("allow") == true
        )
    }

    @Test
    fun deny_removesFromAllowList() {
        // First allow, then deny
        plugin.execute("allow|+15559876543")
        val result = plugin.execute("deny|+15559876543")
        assertTrue("Deny command should succeed", result.isSuccess)
        assertTrue(
            "Success message should mention removal or deny",
            result.data?.contains("Removed") == true ||
                    result.data?.contains("+15559876543") == true
        )
    }

    @Test
    fun listAllowed_showsAllowedNumbers() {
        plugin.execute("allow|+15551111111")
        plugin.execute("allow|+15552222222")

        val result = plugin.execute("list_allowed")
        assertTrue("list_allowed should succeed", result.isSuccess)
        assertTrue(
            "Result should contain allowed numbers",
            result.data?.contains("+15551111111") == true &&
                    result.data?.contains("+15552222222") == true
        )
    }

    @Test
    fun rateStatus_showsSentCount() {
        val result = plugin.execute("rate_status")
        assertTrue("rate_status should succeed", result.isSuccess)
        assertTrue(
            "Rate status should show sent count",
            result.data?.contains("Sent") == true || result.data?.contains("0") == true
        )
    }

    @Test
    fun sendWithMissingParts_returnsError() {
        // send command without enough parts (missing message)
        val result = plugin.execute("send|+15551234567")
        assertFalse("Send with missing message should fail", result.isSuccess)
    }

    @Test
    fun pluginId_isSMSSend() {
        assertEquals("Plugin ID should be 'sms_send'", "sms_send", plugin.id)
    }
}
