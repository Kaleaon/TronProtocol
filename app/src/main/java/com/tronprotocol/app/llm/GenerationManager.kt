package com.tronprotocol.app.llm

import android.util.Log
import com.tronprotocol.app.llm.backend.BackendGenerationResult
import com.tronprotocol.app.llm.backend.BackendSessionConfig
import com.tronprotocol.app.llm.backend.BackendType
import com.tronprotocol.app.llm.backend.LLMBackend
import com.tronprotocol.app.llm.backend.StreamCallback
import com.tronprotocol.app.persona.ControlVectorManager
import com.tronprotocol.app.persona.EmotionalStateTracker
import com.tronprotocol.app.persona.Persona
import com.tronprotocol.app.plugins.ToolCallResult
import com.tronprotocol.app.plugins.ToolDefinition

/**
 * Unified generation facade across all backends.
 *
 * Ported from ToolNeuron's GenerationManager. Provides a single API for:
 * - Text generation (single-turn and multi-turn)
 * - Streaming with token callbacks
 * - Persona-aware generation with control vector steering
 * - Grammar-constrained tool calling with plugin dispatch
 * - KV cache persistence for conversation resume
 * - Image generation via diffusion backends
 *
 * Orchestrates [OnDeviceLLMManager], [ControlVectorManager],
 * [EmotionalStateTracker], and the tool calling system.
 */
class GenerationManager(
    private val llmManager: OnDeviceLLMManager,
    private val controlVectorManager: ControlVectorManager = ControlVectorManager(),
    private val emotionalStateTracker: EmotionalStateTracker = EmotionalStateTracker()
) {

    /** The currently active persona. */
    var activePersona: Persona? = null
        private set

    /** The active backend being used for generation. */
    val activeBackend: LLMBackend?
        get() = llmManager.activeBackend

    /** Whether a model is currently loaded. */
    val isModelLoaded: Boolean
        get() = llmManager.modelState == OnDeviceLLMManager.ModelState.READY

    /** Tool calling state. */
    private var toolsEnabled = false
    private var currentToolsJson: String? = null

    /** Tool call dispatch handler. */
    var toolCallHandler: ((name: String, argsJson: String) -> ToolCallResult)? = null

    /**
     * Load a model with the given config.
     */
    fun loadModel(config: LLMModelConfig): Boolean {
        val success = llmManager.loadModel(config)
        if (success) {
            val backend = llmManager.activeBackend
            if (backend != null) {
                controlVectorManager.bind(backend)
                activePersona?.let { controlVectorManager.applyPersonality(it) }
            }
        }
        return success
    }

    /**
     * Set the active persona and apply its personality to the backend.
     */
    fun setPersona(persona: Persona) {
        activePersona = persona
        val backend = llmManager.activeBackend
        if (backend != null) {
            controlVectorManager.applyPersonality(persona)
        }
        Log.d(TAG, "Persona set: ${persona.name}")
    }

    /**
     * Clear the active persona and reset personality steering.
     */
    fun clearPersona() {
        activePersona = null
        controlVectorManager.clearAllInterventions()
        Log.d(TAG, "Persona cleared")
    }

    /**
     * Generate text with the current persona and backend.
     */
    fun generate(prompt: String, maxTokens: Int = 512): BackendGenerationResult {
        val backend = llmManager.activeBackend
            ?: return BackendGenerationResult.error("No backend loaded")

        val persona = activePersona
        val effectivePrompt = if (persona != null) {
            val systemPrompt = persona.buildEffectiveSystemPrompt()
            "$systemPrompt\n\nUser: $prompt\nAssistant:"
        } else prompt

        val config = llmManager.activeConfig
        val result = backend.generate(
            effectivePrompt,
            maxTokens,
            config?.temperature ?: 0.7f,
            config?.topP ?: 0.9f
        )

        // Update emotional state from generation result
        if (result.success && result.text != null) {
            emotionalStateTracker.processTurn(prompt, result.text)
            controlVectorManager.updateEmotionState(emotionalStateTracker.currentRegime)
        }

        return result
    }

    /**
     * Generate with streaming callbacks.
     */
    fun generateStreaming(
        prompt: String,
        maxTokens: Int = 512,
        callback: StreamCallback
    ) {
        val backend = llmManager.activeBackend
        if (backend == null) {
            callback.onError("No backend loaded")
            return
        }

        val persona = activePersona
        val effectivePrompt = if (persona != null) {
            val systemPrompt = persona.buildEffectiveSystemPrompt()
            "$systemPrompt\n\nUser: $prompt\nAssistant:"
        } else prompt

        val config = llmManager.activeConfig

        // Wrap callback to intercept tool calls and track emotion
        val wrappedCallback = object : StreamCallback {
            private val responseBuilder = StringBuilder()

            override fun onToken(token: String) {
                responseBuilder.append(token)
                callback.onToken(token)
            }

            override fun onComplete(tokensGenerated: Int, latencyMs: Long) {
                val response = responseBuilder.toString()
                emotionalStateTracker.processTurn(prompt, response)
                controlVectorManager.updateEmotionState(emotionalStateTracker.currentRegime)
                controlVectorManager.onGenerationTurnComplete(0.5f) // neutral quality signal
                callback.onComplete(tokensGenerated, latencyMs)
            }

            override fun onError(error: String) {
                callback.onError(error)
            }

            override fun onToolCall(name: String, argsJson: String) {
                // Dispatch tool call to handler
                val handler = toolCallHandler
                if (handler != null) {
                    val result = handler(name, argsJson)
                    if (result.success) {
                        callback.onToolCall(name, result.resultJson)
                    } else {
                        callback.onError("Tool call failed: ${result.error}")
                    }
                } else {
                    callback.onToolCall(name, argsJson)
                }
            }
        }

        backend.generateStreaming(
            effectivePrompt,
            maxTokens,
            config?.temperature ?: 0.7f,
            config?.topP ?: 0.9f,
            wrappedCallback
        )
    }

    /**
     * Generate from a multi-turn conversation.
     *
     * @param messagesJson JSON array of {"role": "...", "content": "..."} messages
     */
    fun generateMultiTurn(messagesJson: String, maxTokens: Int = 512, callback: StreamCallback) {
        val backend = llmManager.activeBackend
        if (backend == null) {
            callback.onError("No backend loaded")
            return
        }

        val config = llmManager.activeConfig
        backend.generateMultiTurn(
            messagesJson,
            maxTokens,
            config?.temperature ?: 0.7f,
            config?.topP ?: 0.9f,
            callback
        )
    }

    /**
     * Enable tool calling with the given tool definitions.
     *
     * @param tools List of tool definitions to register
     * @param grammarMode 0 = STRICT (force JSON), 1 = LAZY (model chooses)
     */
    fun enableToolCalling(tools: List<ToolDefinition>, grammarMode: Int = 0) {
        val backend = llmManager.activeBackend ?: return
        if (!backend.isToolCallingSupported()) {
            Log.w(TAG, "Backend ${backend.name} does not support tool calling")
            return
        }

        val toolsJson = ToolDefinition.toToolsJson(tools)
        backend.enableToolCalling(toolsJson, grammarMode, useTypedGrammar = true)
        currentToolsJson = toolsJson
        toolsEnabled = true
        Log.d(TAG, "Tool calling enabled with ${tools.size} tools (mode=$grammarMode)")
    }

    /**
     * Disable tool calling.
     */
    fun disableToolCalling() {
        llmManager.activeBackend?.clearTools()
        toolsEnabled = false
        currentToolsJson = null
    }

    /**
     * Save the current KV cache state for conversation resume.
     */
    fun saveConversationState(path: String): Boolean {
        return llmManager.activeBackend?.saveState(path) ?: false
    }

    /**
     * Restore a previously saved KV cache state.
     */
    fun restoreConversationState(path: String): Boolean {
        return llmManager.activeBackend?.loadState(path) ?: false
    }

    /**
     * Get the emotional state tracker for direct access.
     */
    fun getEmotionalStateTracker(): EmotionalStateTracker = emotionalStateTracker

    /**
     * Get the control vector manager for direct access.
     */
    fun getControlVectorManager(): ControlVectorManager = controlVectorManager

    /**
     * Shutdown all resources.
     */
    fun shutdown() {
        controlVectorManager.unbind()
        disableToolCalling()
        llmManager.shutdown()
        Log.d(TAG, "GenerationManager shut down")
    }

    companion object {
        private const val TAG = "GenerationManager"
    }
}
