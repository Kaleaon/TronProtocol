package com.tronprotocol.app.plugins

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PluginManagerTest {

    private lateinit var pluginManager: PluginManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        pluginManager = PluginManager.getInstance()
        context = RuntimeEnvironment.getApplication()
        pluginManager.initialize(context)
    }

    @After
    fun tearDown() {
        pluginManager.destroy()
    }

    // --- Helper test plugin ---

    private class TestPlugin(override val id: String = "test_plugin") : Plugin {
        override val name = "Test"
        override val description = "Test plugin"
        override var isEnabled = true
        var lastInput: String? = null
        override fun execute(input: String): PluginResult {
            lastInput = input
            return PluginResult.success("ok", 0)
        }
        override fun initialize(context: Context) {}
        override fun destroy() {}
    }

    // --- Tests ---

    @Test
    fun registerPlugin_addsPluginToManager() {
        val plugin = TestPlugin()
        val registered = pluginManager.registerPlugin(plugin)
        assertTrue("registerPlugin should return true", registered)
        assertNotNull("Plugin should be retrievable after registration", pluginManager.getPlugin("test_plugin"))
    }

    @Test
    fun getPlugin_returnsRegisteredPlugin() {
        val plugin = TestPlugin("my_plugin")
        pluginManager.registerPlugin(plugin)

        val retrieved = pluginManager.getPlugin("my_plugin")
        assertNotNull(retrieved)
        assertEquals("my_plugin", retrieved!!.id)
        assertSame(plugin, retrieved)
    }

    @Test
    fun getPlugin_returnsNullForUnknownId() {
        val result = pluginManager.getPlugin("nonexistent_plugin")
        assertNull("getPlugin should return null for unknown plugin id", result)
    }

    @Test
    fun executePlugin_returnsResultForRegisteredPlugin() {
        val plugin = TestPlugin()
        pluginManager.registerPlugin(plugin)

        val result = pluginManager.executePlugin("test_plugin", "hello world")
        assertTrue("executePlugin should succeed for a registered plugin", result.isSuccess)
        assertEquals("ok", result.data)
        assertEquals("hello world", plugin.lastInput)
    }

    @Test
    fun executePlugin_returnsErrorForUnknownPlugin() {
        val result = pluginManager.executePlugin("unknown_plugin", "input")
        assertFalse("executePlugin should fail for unknown plugin", result.isSuccess)
        assertTrue(
            "Error message should mention plugin not found",
            result.errorMessage?.contains("not found") == true
        )
    }

    @Test
    fun unregisterPlugin_removesPlugin() {
        val plugin = TestPlugin()
        pluginManager.registerPlugin(plugin)
        assertNotNull(pluginManager.getPlugin("test_plugin"))

        pluginManager.unregisterPlugin("test_plugin")
        assertNull(
            "Plugin should be null after unregistration",
            pluginManager.getPlugin("test_plugin")
        )
    }

    @Test
    fun disablePlugin_setsIsEnabledToFalse() {
        val plugin = TestPlugin()
        pluginManager.registerPlugin(plugin)
        assertTrue("Plugin should start enabled", plugin.isEnabled)

        // Disable the plugin by getting it and setting isEnabled to false
        pluginManager.getPlugin("test_plugin")!!.isEnabled = false
        assertFalse("Plugin should be disabled", plugin.isEnabled)
    }

    @Test
    fun executePlugin_returnsErrorForDisabledPlugin() {
        val plugin = TestPlugin()
        pluginManager.registerPlugin(plugin)
        plugin.isEnabled = false

        val result = pluginManager.executePlugin("test_plugin", "input")
        assertFalse("executePlugin should fail for disabled plugin", result.isSuccess)
        assertTrue(
            "Error message should mention disabled",
            result.errorMessage?.contains("disabled") == true
        )
    }

    @Test
    fun getAllPlugins_returnsAllRegisteredPlugins() {
        val plugin1 = TestPlugin("plugin_a")
        val plugin2 = TestPlugin("plugin_b")
        val plugin3 = TestPlugin("plugin_c")

        pluginManager.registerPlugin(plugin1)
        pluginManager.registerPlugin(plugin2)
        pluginManager.registerPlugin(plugin3)

        val allPlugins = pluginManager.getAllPlugins()
        assertEquals("Should have 3 plugins", 3, allPlugins.size)

        val ids = allPlugins.map { it.id }.toSet()
        assertTrue(ids.contains("plugin_a"))
        assertTrue(ids.contains("plugin_b"))
        assertTrue(ids.contains("plugin_c"))
    }
}
