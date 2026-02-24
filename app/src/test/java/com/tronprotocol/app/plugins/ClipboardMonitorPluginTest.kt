package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardMonitorPluginTest {

    private lateinit var context: Context
    private lateinit var plugin: ClipboardMonitorPlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        plugin = ClipboardMonitorPlugin()
        plugin.initialize(context)
        // Clear static history between tests
        ClipboardMonitorPlugin.clipboardHistory.clear()
    }

    @Test
    fun current_returnsClipboardContentOrEmptyMessage() {
        val result = plugin.execute("current")
        assertTrue("current command should succeed", result.isSuccess)
        assertNotNull("Result data should not be null", result.data)
        // In a Robolectric test, clipboard is typically empty
        assertTrue(
            "Should return clipboard content or empty message",
            result.data?.contains("Clipboard") == true || result.data?.isNotEmpty() == true
        )
    }

    @Test
    fun history_returnsEntries() {
        val result = plugin.execute("history")
        assertTrue("history command should succeed", result.isSuccess)
        assertNotNull("History result data should not be null", result.data)
        assertTrue(
            "History should mention entries or show clipboard history",
            result.data?.contains("history") == true ||
                    result.data?.contains("0 entries") == true ||
                    result.data?.contains("Clipboard") == true
        )
    }

    @Test
    fun start_enablesMonitoring() {
        val result = plugin.execute("start")
        assertTrue("start command should succeed", result.isSuccess)
        assertTrue(
            "Start message should confirm monitoring started",
            result.data?.contains("started") == true
        )
    }

    @Test
    fun stop_disablesMonitoring() {
        plugin.execute("start")
        val result = plugin.execute("stop")
        assertTrue("stop command should succeed", result.isSuccess)
        assertTrue(
            "Stop message should confirm monitoring stopped",
            result.data?.contains("stopped") == true
        )
    }

    @Test
    fun clear_emptiesHistory() {
        val result = plugin.execute("clear")
        assertTrue("clear command should succeed", result.isSuccess)
        assertTrue(
            "Clear message should confirm history cleared",
            result.data?.contains("cleared") == true
        )

        // Verify history is empty after clear
        val historyResult = plugin.execute("history")
        assertTrue("History after clear should succeed", historyResult.isSuccess)
        assertTrue(
            "History should show 0 entries after clear",
            historyResult.data?.contains("0 entries") == true
        )
    }

    @Test
    fun pluginId_isClipboardMonitor() {
        assertEquals("Plugin ID should be 'clipboard_monitor'", "clipboard_monitor", plugin.id)
    }
}
