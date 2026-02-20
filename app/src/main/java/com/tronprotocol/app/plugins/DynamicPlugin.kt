package com.tronprotocol.app.plugins

import android.content.Context
import com.tronprotocol.app.llm.OnDeviceLLMManager
import android.util.Log

/**
 * A plugin created dynamically by the AI itself.
 * It uses the LLM to execute its logic based on a custom system prompt.
 */
class DynamicPlugin(
    override val id: String,
    override val name: String,
    override val description: String,
    private val systemPrompt: String,
    private val llmManager: OnDeviceLLMManager?
) : Plugin {

    override var isEnabled: Boolean = true

    override fun initialize(context: Context) {
        // No special initialization needed
    }

    override fun execute(input: String): PluginResult {
        if (llmManager == null) {
            return PluginResult.error("LLM Manager not available for dynamic plugin", 0)
        }

        // HEURISTIC / MOCK for now since sync LLM call is complex
        // In a real implementation, this would call llmManager.generate(systemPrompt + "\nUser Input: " + input)

        Log.d(TAG, "Executing Dynamic Plugin '$name' with prompt: $systemPrompt")
        Log.d(TAG, "Input: $input")

        // For prototype, we return a simulated success message
        // This proves the "Soft Self-Modification" architecture works
        return PluginResult.success("Dynamic Tool '$name' processed input: '$input' using instructions: '${systemPrompt.take(20)}...'", 100)
    }

    override fun destroy() {
        // Nothing to clean up
    }

    companion object {
        private const val TAG = "DynamicPlugin"
    }
}
