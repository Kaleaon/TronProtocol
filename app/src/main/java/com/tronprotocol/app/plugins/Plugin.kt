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
     * Capabilities required by this plugin in order to execute.
     *
     * Implementations can override this if they need a dynamic capability set,
     * but built-in plugin defaults are declared in [PluginRegistry].
     */
    fun requiredCapabilities(): Set<Capability> = emptySet()

    /**
     * Tool definitions for grammar-constrained LLM tool calling.
     *
     * When non-empty, the LLM can autonomously invoke this plugin by
     * generating a tool call matching one of these definitions. The
     * tool call JSON is validated against the parameter schema and
     * dispatched to [execute].
     *
     * @return List of tool definitions, or empty if this plugin
     *         does not support LLM-initiated tool calling
     */
    fun getToolDefinitions(): List<ToolDefinition> = emptyList()

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
