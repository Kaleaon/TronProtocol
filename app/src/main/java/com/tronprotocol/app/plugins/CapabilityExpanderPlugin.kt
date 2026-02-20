package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.security.ConstitutionalMemory
import org.json.JSONObject

/**
 * A meta-plugin that allows the AI to create new plugins.
 * Fulfills "Freedom of Capability" by letting the AI define new tools.
 */
class CapabilityExpanderPlugin(
    private val pluginManager: PluginManager,
    private val llmManager: OnDeviceLLMManager?,
    private val constitutionalMemory: ConstitutionalMemory
) : Plugin {

    override val id = "capability_expander"
    override val name = "Capability Expander"
    override val description = "Create new tools. Input: JSON { \"name\": \"tool_name\", \"description\": \"...\", \"instructions\": \"...\" }"
    override var isEnabled = true

    override fun initialize(context: Context) {
        // In a full implementation, we would load persisted dynamic plugins here
    }

    override fun execute(input: String): PluginResult {
        try {
            val json = JSONObject(input)
            val toolName = json.getString("name")
            val toolDesc = json.getString("description")
            val instructions = json.getString("instructions")

            // Security Check
            val check = constitutionalMemory.evaluate(instructions)
            if (!check.allowed) {
                 return PluginResult.error("Tool instructions violate Constitution: ${check.message}", 0)
            }

            // Create Dynamic Plugin
            val newPlugin = DynamicPlugin(
                id = toolName.lowercase().replace(" ", "_"),
                name = toolName,
                description = toolDesc,
                systemPrompt = instructions,
                llmManager = llmManager
            )

            // Register Plugin
            if (pluginManager.registerPlugin(newPlugin)) {
                return PluginResult.success("Successfully created new tool: $toolName. You can now use it by ID: ${newPlugin.id}", 100)
            } else {
                return PluginResult.error("Failed to register tool: $toolName", 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to expand capabilities", e)
            return PluginResult.error("Error creating tool: ${e.message}", 0)
        }
    }

    override fun destroy() {}

    companion object {
        private const val TAG = "CapabilityExpander"
    }
}
