package com.tronprotocol.app.llm.backend

/**
 * Abstraction over LLM inference backends (MNN, GGUF/llama.cpp, etc.).
 *
 * Allows TronProtocol to route inference to the best available backend
 * for a given model format, with a consistent API across all backends.
 */
interface LLMBackend {

    /** Human-readable backend identifier: "mnn" or "gguf". */
    val name: String

    /** Whether the native libraries for this backend were successfully loaded. */
    val isAvailable: Boolean

    /**
     * Load a model from the given path.
     *
     * @param modelPath Directory (MNN) or file path (GGUF)
     * @param config Session configuration
     * @return true if the model was loaded successfully
     */
    fun loadModel(modelPath: String, config: BackendSessionConfig): Boolean

    /**
     * Generate text synchronously.
     *
     * @return Result containing generated text and performance metrics
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): BackendGenerationResult

    /**
     * Generate text with streaming token-by-token callback.
     * Default implementation delegates to synchronous [generate].
     */
    fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: StreamCallback
    ) {
        val result = generate(prompt, maxTokens, temperature, topP)
        if (result.error != null) {
            callback.onError(result.error)
        } else {
            callback.onToken(result.text ?: "")
            callback.onComplete(result.tokensGenerated, result.latencyMs)
        }
    }

    /**
     * Generate from a multi-turn conversation (JSON message array).
     * Default implementation falls back to single-turn with last user message.
     */
    fun generateMultiTurn(
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: StreamCallback
    ) {
        // Default: extract last user message and delegate to single-turn
        generateStreaming(messagesJson, maxTokens, temperature, topP, callback)
    }

    /** Get the number of tokens generated in the last call. */
    fun getLastTokenCount(): Int = 0

    /** Unload the current model and free native resources. */
    fun unload()

    /** Get backend-specific stats (JSON string or null). */
    fun getStats(): String? = null

    /** Get info about the currently loaded model (JSON string or null). */
    fun getModelInfo(): String? = null

    // ---- Tool calling (optional, GGUF-specific) ----

    /** Whether this backend supports grammar-constrained tool calling. */
    fun isToolCallingSupported(): Boolean = false

    /**
     * Enable tool calling with grammar constraints.
     *
     * @param toolsJson OpenAI-format JSON tool definitions
     * @param grammarMode 0 = STRICT (force JSON), 1 = LAZY (model chooses)
     * @param useTypedGrammar Whether to use typed grammar for parameters
     */
    fun enableToolCalling(toolsJson: String, grammarMode: Int, useTypedGrammar: Boolean): Boolean = false

    /** Clear tool calling grammar constraints. */
    fun clearTools() {}

    // ---- Persona engine (optional, GGUF-specific) ----

    /** Update sampler parameters at runtime (JSON: temperature, topP, topK, etc.). */
    fun updateSamplerParams(paramsJson: String): Boolean = false

    /** Apply per-token logit bias (JSON: token_id → bias_value). */
    fun setLogitBias(biasJson: String): Boolean = false

    /** Load control vectors for personality steering. */
    fun loadControlVectors(vectorsJson: String): Boolean = false

    /** Clear any loaded control vectors. */
    fun clearControlVector(): Boolean = false

    // ---- KV cache persistence (optional) ----

    /** Save the current KV cache state for conversation resume. */
    fun saveState(path: String): Boolean = false

    /** Restore a previously saved KV cache state. */
    fun loadState(path: String): Boolean = false

    /** Shutdown the backend entirely and release all resources. */
    fun shutdown() { unload() }
}

/**
 * Configuration for creating a backend inference session.
 */
data class BackendSessionConfig(
    val backend: Int = 0,
    val numThreads: Int = 4,
    val contextWindow: Int = 4096,
    val maxTokens: Int = 512,
    val useMmap: Boolean = false,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.0f,
    val flashAttn: Boolean = false,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val systemPrompt: String = "",
    val chatTemplate: String = ""
)

/**
 * Result of a backend generation call.
 */
data class BackendGenerationResult(
    val text: String?,
    val tokensGenerated: Int,
    val latencyMs: Long,
    val tokensPerSecond: Float = 0f,
    val error: String? = null
) {
    val success: Boolean get() = error == null && text != null

    companion object {
        fun success(text: String, tokens: Int, latencyMs: Long): BackendGenerationResult {
            val tps = if (tokens > 0 && latencyMs > 0) (tokens * 1000f) / latencyMs else 0f
            return BackendGenerationResult(text, tokens, latencyMs, tps)
        }

        fun error(message: String) = BackendGenerationResult(null, 0, 0, 0f, message)
    }
}

/**
 * Callback for streaming token generation.
 */
interface StreamCallback {
    fun onToken(token: String)
    fun onComplete(tokensGenerated: Int, latencyMs: Long)
    fun onError(error: String)
    fun onToolCall(name: String, argsJson: String) {}
}
