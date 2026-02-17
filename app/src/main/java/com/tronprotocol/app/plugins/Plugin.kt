package com.tronprotocol.app.plugins

import android.content.Context

/**
 * Base interface for plugins in TronProtocol
 *
 * Inspired by ToolNeuron's extensible plugin architecture
 */
interface Plugin {
    /** Unique identifier for this plugin */
    val id: String

    /** Display name of this plugin */
    val name: String

    /** Description of what this plugin does */
    val description: String

    /** Whether the plugin is enabled */
    var isEnabled: Boolean

    /**
     * Preferred typed execution entrypoint.
     */
    @Throws(Exception::class)
    fun execute(request: PluginRequest): PluginResponse {
        return PluginResponse.fromResult(execute(request.rawInput))
    }

    /**
     * Execute the plugin's main functionality
     * @param input Input data for the plugin
     * @return Result of plugin execution
     */
    @Throws(Exception::class)
    fun execute(input: String): PluginResult

    /** Initialize the plugin with context */
    fun initialize(context: Context)

    /** Clean up resources when plugin is destroyed */
    fun destroy()
}
