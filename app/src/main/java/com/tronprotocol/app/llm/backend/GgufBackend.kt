package com.tronprotocol.app.llm.backend

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GGUF/llama.cpp backend implementation, ported from ToolNeuron's GGUFEngine.
 *
 * Provides on-device LLM inference using GGUF-format models via llama.cpp's
 * native library. Supports features that MNN lacks:
 * - Grammar-constrained tool calling (STRICT/LAZY modes)
 * - Multi-turn conversation with message array support
 * - Control vector and logit bias for persona steering
 * - KV cache state persistence for conversation resume
 *
 * @see <a href="https://github.com/Siddhesh2377/ToolNeuron">ToolNeuron</a>
 * @see <a href="https://github.com/ggerganov/llama.cpp">llama.cpp</a>
 */
class GgufBackend : LLMBackend {

    override val name: String = "gguf"

    override val isAvailable: Boolean
        get() = nativeAvailable.get()

    private val sessionHandle = AtomicLong(0L)
    private var lastTokenCount: Int = 0
    private var currentToolsJson: String? = null

    /** Grammar mode: 0 = STRICT (force JSON tool call), 1 = LAZY (model chooses). */
    enum class GrammarMode(val value: Int) {
        STRICT(0),
        LAZY(1)
    }

    override fun loadModel(modelPath: String, config: BackendSessionConfig): Boolean {
        if (!isAvailable) {
            Log.e(TAG, "Cannot load model — GGUF native libraries not available")
            return false
        }

        if (sessionHandle.get() != 0L) {
            unload()
        }

        return try {
            val handle = nativeLoadModel(
                path = modelPath,
                threads = config.numThreads,
                ctxSize = config.contextWindow,
                temp = config.temperature,
                topK = config.topK,
                topP = config.topP,
                minP = config.minP,
                mirostat = 0,
                mirostatTau = 5.0f,
                mirostatEta = 0.1f,
                seed = -1,
                flashAttn = config.flashAttn,
                cacheTypeK = config.cacheTypeK,
                cacheTypeV = config.cacheTypeV
            )
            if (handle == 0L) {
                Log.e(TAG, "nativeLoadModel returned null handle")
                false
            } else {
                sessionHandle.set(handle)
                if (config.systemPrompt.isNotEmpty()) {
                    nativeSetSystemPrompt(config.systemPrompt)
                }
                Log.d(TAG, "GGUF model loaded from $modelPath (threads=${config.numThreads}, ctx=${config.contextWindow})")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GGUF model: ${e.message}", e)
            false
        }
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): BackendGenerationResult {
        val handle = sessionHandle.get()
        if (handle == 0L) {
            return BackendGenerationResult.error("No GGUF model loaded")
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = nativeGenerate(handle, prompt, maxTokens)
            val latencyMs = System.currentTimeMillis() - startTime

            if (result.isNullOrEmpty()) {
                BackendGenerationResult.error("GGUF model returned empty response")
            } else {
                lastTokenCount = nativeGetTokenCount(handle)
                BackendGenerationResult.success(result, lastTokenCount, latencyMs)
            }
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "GGUF generation failed after ${latencyMs}ms: ${e.message}", e)
            BackendGenerationResult.error("GGUF generation failed: ${e.message}")
        }
    }

    override fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: StreamCallback
    ) {
        val handle = sessionHandle.get()
        if (handle == 0L) {
            callback.onError("No GGUF model loaded")
            return
        }

        val startTime = System.currentTimeMillis()
        try {
            nativeGenerateStreaming(handle, prompt, maxTokens, object : NativeStreamCallback {
                override fun onToken(token: String) {
                    callback.onToken(token)
                }

                override fun onToolCall(name: String, argsJson: String) {
                    callback.onToolCall(name, argsJson)
                }

                override fun onComplete() {
                    val latencyMs = System.currentTimeMillis() - startTime
                    lastTokenCount = nativeGetTokenCount(handle)
                    callback.onComplete(lastTokenCount, latencyMs)
                }

                override fun onError(message: String) {
                    callback.onError(message)
                }
            })
        } catch (e: Exception) {
            callback.onError("GGUF streaming failed: ${e.message}")
        }
    }

    override fun generateMultiTurn(
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: StreamCallback
    ) {
        val handle = sessionHandle.get()
        if (handle == 0L) {
            callback.onError("No GGUF model loaded")
            return
        }

        val startTime = System.currentTimeMillis()
        try {
            nativeGenerateMultiTurn(handle, messagesJson, maxTokens, object : NativeStreamCallback {
                override fun onToken(token: String) {
                    callback.onToken(token)
                }

                override fun onToolCall(name: String, argsJson: String) {
                    callback.onToolCall(name, argsJson)
                }

                override fun onComplete() {
                    val latencyMs = System.currentTimeMillis() - startTime
                    lastTokenCount = nativeGetTokenCount(handle)
                    callback.onComplete(lastTokenCount, latencyMs)
                }

                override fun onError(message: String) {
                    callback.onError(message)
                }
            })
        } catch (e: Exception) {
            callback.onError("GGUF multi-turn failed: ${e.message}")
        }
    }

    override fun getLastTokenCount(): Int = lastTokenCount

    override fun unload() {
        val handle = sessionHandle.getAndSet(0L)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeUnloadModel(handle)
                Log.d(TAG, "GGUF model unloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading GGUF model: ${e.message}", e)
            }
        }
        lastTokenCount = 0
        currentToolsJson = null
    }

    override fun getStats(): String? {
        val handle = sessionHandle.get()
        if (handle == 0L) return null
        return try {
            nativeGetModelInfo(handle)
        } catch (e: Exception) {
            null
        }
    }

    override fun getModelInfo(): String? = getStats()

    // ---- Tool calling ----

    override fun isToolCallingSupported(): Boolean = isAvailable

    override fun enableToolCalling(
        toolsJson: String,
        grammarMode: Int,
        useTypedGrammar: Boolean
    ): Boolean {
        if (sessionHandle.get() == 0L) return false

        // Cache optimization: skip grammar rebuild if tools haven't changed
        if (toolsJson == currentToolsJson) {
            Log.d(TAG, "Tools unchanged, skipping grammar rebuild")
            return true
        }

        return try {
            val success = nativeEnableToolCalling(toolsJson, grammarMode, useTypedGrammar)
            if (success) {
                currentToolsJson = toolsJson
                Log.d(TAG, "Tool calling enabled (grammar=${if (grammarMode == 0) "STRICT" else "LAZY"}, typed=$useTypedGrammar)")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable tool calling: ${e.message}", e)
            false
        }
    }

    override fun clearTools() {
        try {
            nativeClearTools()
            currentToolsJson = null
            Log.d(TAG, "Tool calling grammar cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear tools: ${e.message}", e)
        }
    }

    // ---- Persona engine ----

    override fun updateSamplerParams(paramsJson: String): Boolean {
        if (sessionHandle.get() == 0L) return false
        return try {
            nativeUpdateSamplerParams(paramsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update sampler params: ${e.message}", e)
            false
        }
    }

    override fun setLogitBias(biasJson: String): Boolean {
        if (sessionHandle.get() == 0L) return false
        return try {
            nativeSetLogitBias(biasJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set logit bias: ${e.message}", e)
            false
        }
    }

    override fun loadControlVectors(vectorsJson: String): Boolean {
        if (sessionHandle.get() == 0L) return false
        return try {
            nativeLoadControlVectors(vectorsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load control vectors: ${e.message}", e)
            false
        }
    }

    override fun clearControlVector(): Boolean {
        if (sessionHandle.get() == 0L) return false
        return try {
            nativeClearControlVector()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear control vectors: ${e.message}", e)
            false
        }
    }

    // ---- KV cache persistence ----

    override fun saveState(path: String): Boolean {
        val handle = sessionHandle.get()
        if (handle == 0L) return false
        return try {
            nativeSaveState(handle, path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save KV cache state: ${e.message}", e)
            false
        }
    }

    override fun loadState(path: String): Boolean {
        val handle = sessionHandle.get()
        if (handle == 0L) return false
        return try {
            nativeLoadState(handle, path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load KV cache state: ${e.message}", e)
            false
        }
    }

    /** Native callback interface for streaming generation. */
    interface NativeStreamCallback {
        fun onToken(token: String)
        fun onToolCall(name: String, argsJson: String)
        fun onComplete()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "GgufBackend"

        private val nativeAvailable = AtomicBoolean(false)
        private val loadAttempted = AtomicBoolean(false)

        init {
            tryLoadLibraries()
        }

        @JvmStatic
        fun isNativeAvailable(): Boolean = nativeAvailable.get()

        private fun tryLoadLibraries() {
            if (loadAttempted.getAndSet(true)) return

            try {
                System.loadLibrary("ai_gguf")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libai_gguf.so — GGUF/llama.cpp inference available")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "libai_gguf.so not found, trying alternative names")
                tryAlternativeLibraries()
            }
        }

        private fun tryAlternativeLibraries() {
            // Try llama.cpp's standard library name
            try {
                System.loadLibrary("llama")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libllama.so — GGUF inference available")
                return
            } catch (_: UnsatisfiedLinkError) {}

            // Try ggml
            try {
                System.loadLibrary("ggml")
                System.loadLibrary("llama")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libggml.so + libllama.so — GGUF inference available")
                return
            } catch (_: UnsatisfiedLinkError) {}

            Log.w(TAG, "No GGUF native libraries found — GGUF inference unavailable. " +
                    "Add libai_gguf.so to jniLibs/arm64-v8a/ to enable.")
        }

        // ---- JNI native methods ----

        @JvmStatic
        private external fun nativeLoadModel(
            path: String, threads: Int, ctxSize: Int,
            temp: Float, topK: Int, topP: Float, minP: Float,
            mirostat: Int, mirostatTau: Float, mirostatEta: Float,
            seed: Int, flashAttn: Boolean, cacheTypeK: String, cacheTypeV: String
        ): Long

        @JvmStatic
        private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String?

        @JvmStatic
        private external fun nativeGenerateStreaming(
            handle: Long, prompt: String, maxTokens: Int, callback: NativeStreamCallback
        )

        @JvmStatic
        private external fun nativeGenerateMultiTurn(
            handle: Long, messagesJson: String, maxTokens: Int, callback: NativeStreamCallback
        )

        @JvmStatic
        private external fun nativeGetTokenCount(handle: Long): Int

        @JvmStatic
        private external fun nativeGetModelInfo(handle: Long): String?

        @JvmStatic
        private external fun nativeUnloadModel(handle: Long)

        @JvmStatic
        private external fun nativeSetSystemPrompt(prompt: String)

        @JvmStatic
        private external fun nativeEnableToolCalling(
            toolsJson: String, grammarMode: Int, useTypedGrammar: Boolean
        ): Boolean

        @JvmStatic
        private external fun nativeClearTools()

        @JvmStatic
        private external fun nativeUpdateSamplerParams(paramsJson: String): Boolean

        @JvmStatic
        private external fun nativeSetLogitBias(biasJson: String): Boolean

        @JvmStatic
        private external fun nativeLoadControlVectors(vectorsJson: String): Boolean

        @JvmStatic
        private external fun nativeClearControlVector(): Boolean

        @JvmStatic
        private external fun nativeSaveState(handle: Long, path: String): Boolean

        @JvmStatic
        private external fun nativeLoadState(handle: Long, path: String): Boolean
    }
}
