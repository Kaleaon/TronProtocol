package com.tronprotocol.app.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ScreenReaderPluginTest {

    private lateinit var plugin: ScreenReaderPlugin
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        plugin = ScreenReaderPlugin()
        mockContext = mock(Context::class.java)
        plugin.initialize(mockContext)

        // Reset TronAccessibilityService state before each test
        TronAccessibilityService.currentScreenSnapshot = null
        TronAccessibilityService.recentEvents.clear()
        TronAccessibilityService.serviceConnected = false
    }

    @After
    fun tearDown() {
        plugin.destroy()
    }

    @Test
    fun testPluginProperties() {
        assertEquals(ScreenReaderPlugin.ID, plugin.id)
        assertEquals("Screen Reader", plugin.name)
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun testSnapshotCommandWithNoSnapshot() {
        val result = plugin.execute("snapshot")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage?.contains("No screen snapshot available") == true)
    }

    @Test
    fun testSnapshotCommandWithSnapshot() {
        val snapshot = JSONObject().apply {
            put("timestamp", 123456789L)
            put("packageName", "com.example.app")
            put("nodes", JSONArray())
        }
        TronAccessibilityService.currentScreenSnapshot = snapshot

        val result = plugin.execute("snapshot")
        assertTrue(result.isSuccess)
        assertEquals(snapshot.toString(2), result.data)
    }

    @Test
    fun testEventsCommandEmpty() {
        val result = plugin.execute("events|10")
        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("Recent 10 events:") == true)
        assertTrue(result.data?.contains("[]") == true)
    }

    @Test
    fun testEventsCommandWithEvents() {
        val event1 = JSONObject().apply { put("eventType", "TYPE_WINDOW_STATE_CHANGED") }
        val event2 = JSONObject().apply { put("eventType", "TYPE_VIEW_CLICKED") }
        TronAccessibilityService.recentEvents.add(event1)
        TronAccessibilityService.recentEvents.add(event2)

        val result = plugin.execute("events|1")
        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("Recent 1 events:") == true)
        assertTrue(result.data?.contains("TYPE_WINDOW_STATE_CHANGED") == true)
        assertFalse(result.data?.contains("TYPE_VIEW_CLICKED") == true)

        val resultAll = plugin.execute("events|5")
        assertTrue(resultAll.isSuccess)
        assertTrue(resultAll.data?.contains("Recent 5 events:") == true)
        assertTrue(resultAll.data?.contains("TYPE_WINDOW_STATE_CHANGED") == true)
        assertTrue(resultAll.data?.contains("TYPE_VIEW_CLICKED") == true)
    }

    @Test
    fun testStatusCommand() {
        val resultDisconnected = plugin.execute("status")
        assertTrue(resultDisconnected.isSuccess)
        assertTrue(resultDisconnected.data?.contains("DISCONNECTED") == true)
        assertTrue(resultDisconnected.data?.contains("Buffered events: 0") == true)
        assertTrue(resultDisconnected.data?.contains("Has screen snapshot: false") == true)

        TronAccessibilityService.serviceConnected = true
        TronAccessibilityService.recentEvents.add(JSONObject())
        TronAccessibilityService.currentScreenSnapshot = JSONObject()

        val resultConnected = plugin.execute("status")
        assertTrue(resultConnected.isSuccess)
        assertTrue(resultConnected.data?.contains("CONNECTED") == true)
        assertTrue(resultConnected.data?.contains("Buffered events: 1") == true)
        assertTrue(resultConnected.data?.contains("Has screen snapshot: true") == true)
    }

    @Test
    fun testFindCommandUsageError() {
        val result = plugin.execute("find")
        assertFalse(result.isSuccess)
        assertEquals("Usage: find|text", result.errorMessage)
    }

    @Test
    fun testFindCommandNoSnapshotError() {
        val result = plugin.execute("find|search_term")
        assertFalse(result.isSuccess)
        assertEquals("No screen snapshot available", result.errorMessage)
    }

    @Test
    fun testFindCommandSuccess() {
        val nodes = JSONArray().apply {
            put(JSONObject().apply { put("text", "Hello World"); put("class", "android.widget.TextView") })
            put(JSONObject().apply { put("description", "Search Button"); put("class", "android.widget.Button") })
            put(JSONObject().apply { put("text", "Cancel"); put("class", "android.widget.Button") })
        }
        val snapshot = JSONObject().apply { put("nodes", nodes) }
        TronAccessibilityService.currentScreenSnapshot = snapshot

        // Search for "world" (case insensitive)
        val result1 = plugin.execute("find|world")
        assertTrue(result1.isSuccess)
        assertTrue(result1.data?.contains("Found 1 matches:") == true)
        assertTrue(result1.data?.contains("Hello World") == true)

        // Search for "button" (in description)
        val result2 = plugin.execute("find|button")
        assertTrue(result2.isSuccess)
        assertTrue(result2.data?.contains("Found 1 matches:") == true)
        assertTrue(result2.data?.contains("Search Button") == true)

        // Search for multiple matches (e.g. by common letter)
        val result3 = plugin.execute("find|e")
        assertTrue(result3.isSuccess)
        assertTrue(result3.data?.contains("Found 3 matches:") == true) // Hello World, Search Button, Cancel all contain 'e'

        // Search for no matches
        val result4 = plugin.execute("find|xyz")
        assertTrue(result4.isSuccess)
        assertTrue(result4.data?.contains("Found 0 matches:") == true)
    }

    @Test
    fun testUnknownCommand() {
        val result = plugin.execute("invalid_command")
        assertFalse(result.isSuccess)
        assertEquals("Unknown command: invalid_command", result.errorMessage)
    }
}
