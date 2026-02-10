package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log

/**
 * Manages all plugins in the TronProtocol system
 *
 * Inspired by ToolNeuron's plugin management architecture
 */
class PluginManager private constructor() {

    private val plugins = mutableMapOf<String, Plugin>()
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
        Log.d(TAG, "PluginManager initialized")
    }

    /**
     * Register a plugin
     */
    fun registerPlugin(plugin: Plugin?): Boolean {
        if (plugin == null) {
            Log.w(TAG, "Skipping plugin registration: plugin is null")
            return false
        }

        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "Skipping plugin registration for ${plugin.javaClass.name}: context is null")
            return false
        }

        return try {
            plugin.initialize(ctx)
            plugins[plugin.id] = plugin
            Log.d(TAG, "Registered plugin: ${plugin.name}")
            true
        } catch (e: Exception) {
            val pluginId = try {
                plugin.id
            } catch (_: Exception) {
                "<unavailable>"
            }
            Log.e(TAG, "Failed to initialize plugin. id=$pluginId, class=${plugin.javaClass.name}", e)
            false
        }
    }

    /**
     * Unregister a plugin
     */
    fun unregisterPlugin(pluginId: String) {
        val plugin = plugins.remove(pluginId)
        if (plugin != null) {
            plugin.destroy()
            Log.d(TAG, "Unregistered plugin: ${plugin.name}")
        }
    }

    /**
     * Get a plugin by ID
     */
    fun getPlugin(pluginId: String): Plugin? = plugins[pluginId]

    /**
     * Get all registered plugins
     */
    fun getAllPlugins(): List<Plugin> = ArrayList(plugins.values)

    /**
     * Get all enabled plugins
     */
    fun getEnabledPlugins(): List<Plugin> = plugins.values.filter { it.isEnabled }

    /**
     * Execute a plugin by ID
     */
    fun executePlugin(pluginId: String, input: String): PluginResult {
        val plugin = plugins[pluginId]
            ?: return PluginResult.error("Plugin not found: $pluginId", 0)

        if (!plugin.isEnabled) {
            return PluginResult.error("Plugin is disabled: $pluginId", 0)
        }

        val startTime = System.currentTimeMillis()

        val guardrail = getGuardrailPlugin()
        if (guardrail != null && PolicyGuardrailPlugin::class.java.name != plugin.javaClass.name) {
            val policy = guardrail.evaluate(pluginId, input)
            if (!policy.isSuccess) {
                return PluginResult.error(
                    policy.errorMessage,
                    System.currentTimeMillis() - startTime
                )
            }
        }

        return try {
            val result = plugin.execute(input)
            Log.d(TAG, "Executed plugin ${plugin.name}: $result")
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Plugin execution failed: ${plugin.name}", e)
            PluginResult.error("Execution failed: ${e.message}", duration)
        }
    }

    private fun getGuardrailPlugin(): PolicyGuardrailPlugin? {
        return plugins["policy_guardrail"] as? PolicyGuardrailPlugin
    }

    /**
     * Clean up all plugins
     */
    fun destroy() {
        for (plugin in plugins.values) {
            plugin.destroy()
        }
        plugins.clear()
        Log.d(TAG, "PluginManager destroyed")
    }

    companion object {
        private const val TAG = "PluginManager"

        @Volatile
        private var instance: PluginManager? = null

        @JvmStatic
        fun getInstance(): PluginManager {
            return instance ?: synchronized(this) {
                instance ?: PluginManager().also { instance = it }
            }
        }
    }
}
