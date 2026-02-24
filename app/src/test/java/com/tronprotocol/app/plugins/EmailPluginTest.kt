package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmailPluginTest {

    private lateinit var context: Context
    private lateinit var plugin: EmailPlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        plugin = EmailPlugin()
        plugin.initialize(context)
    }

    @Test
    fun sendWithoutConfigure_returnsError() {
        val result = plugin.execute("send|user@example.com|Test Subject|Test Body")
        assertFalse("Send without SMTP configuration should fail", result.isSuccess)
        assertTrue(
            "Error should mention SMTP not configured",
            result.errorMessage?.contains("not configured") == true ||
                    result.errorMessage?.contains("configure") == true
        )
    }

    @Test
    fun configure_setsSMTPSettings() {
        val result = plugin.execute("configure|smtp.gmail.com|587|user@gmail.com|app_password")
        assertTrue("Configure command should succeed", result.isSuccess)
        assertTrue(
            "Success message should mention SMTP configured",
            result.data?.contains("configured") == true ||
                    result.data?.contains("user@gmail.com") == true
        )
    }

    @Test
    fun status_showsConfigurationState() {
        // Before configuration
        val statusBefore = plugin.execute("status")
        assertTrue("Status should succeed", statusBefore.isSuccess)
        assertTrue(
            "Status should indicate not configured",
            statusBefore.data?.contains("false") == true ||
                    statusBefore.data?.contains("not set") == true
        )

        // After configuration
        plugin.execute("configure|smtp.example.com|465|test@example.com|password123")
        val statusAfter = plugin.execute("status")
        assertTrue("Status after configure should succeed", statusAfter.isSuccess)
        assertTrue(
            "Status should indicate configured",
            statusAfter.data?.contains("true") == true ||
                    statusAfter.data?.contains("smtp.example.com") == true
        )
    }

    @Test
    fun clearConfig_removesSettings() {
        plugin.execute("configure|smtp.example.com|465|test@example.com|password123")
        val clearResult = plugin.execute("clear_config")
        assertTrue("clear_config should succeed", clearResult.isSuccess)
        assertTrue(
            "Clear message should confirm removal",
            clearResult.data?.contains("cleared") == true
        )

        // Verify config is actually cleared
        val statusResult = plugin.execute("status")
        assertTrue(
            "Status should show not configured after clear",
            statusResult.data?.contains("not set") == true ||
                    statusResult.data?.contains("false") == true
        )
    }

    @Test
    fun history_showsEmptyInitially() {
        val result = plugin.execute("history")
        assertTrue("History should succeed", result.isSuccess)
        assertTrue(
            "History should show 0 entries initially",
            result.data?.contains("0") == true || result.data?.contains("history") == true
        )
    }

    @Test
    fun pluginId_isEmail() {
        assertEquals("Plugin ID should be 'email'", "email", plugin.id)
    }
}
