package com.tronprotocol.app.llm.backend

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MNN (Mobile Neural Network) backend implementation.
 *
 * Wraps the existing MNN JNI bridge that TronProtocol already uses.
 * MNN achieves 8.6x faster prefill and 2.3x faster decode than llama.cpp
 * on mobile ARM processors through optimized NEON/FP16 kernels.
 *
 * This class owns the JNI externals that were previously in OnDeviceLLMManager's
 * companion object.
 */
class MnnBackend : LLMBackend {

    override val name: String = "mnn"

    override val isAvailable: Boolean
        get() = nativeAvailable.get()

    private val sessionHandle = AtomicLong(0L)
    private var lastTokenCount: Int = 0

    override fun loadModel(modelPath: String, config: BackendSessionConfig): Boolean {
        if (!isAvailable) {
            Log.e(TAG, "Cannot load model — MNN native libraries not available")
            return false
        }

        // Unload any existing model
        if (sessionHandle.get() != 0L) {
            unload()
        }

        return try {
            val handle = nativeCreateSession(
                modelPath, config.backend, config.numThreads, config.useMmap
            )
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateSession returned null handle")
                false
            } else {
                sessionHandle.set(handle)
                Log.d(TAG, "MNN model loaded from $modelPath (backend=${config.backend}, threads=${config.numThreads})")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MNN model: ${e.message}", e)
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
            return BackendGenerationResult.error("No MNN model loaded")
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = nativeGenerate(handle, prompt, maxTokens, temperature, topP)
            val latencyMs = System.currentTimeMillis() - startTime

            if (result.isNullOrEmpty()) {
                BackendGenerationResult.error("MNN model returned empty response")
            } else {
                lastTokenCount = nativeGetLastTokenCount(handle)
                BackendGenerationResult.success(result, lastTokenCount, latencyMs)
            }
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "MNN generation failed after ${latencyMs}ms: ${e.message}", e)
            BackendGenerationResult.error("MNN generation failed: ${e.message}")
        }
    }

    override fun getLastTokenCount(): Int = lastTokenCount

    override fun unload() {
        val handle = sessionHandle.getAndSet(0L)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroySession(handle)
                Log.d(TAG, "MNN model unloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying MNN session: ${e.message}", e)
            }
        }
        lastTokenCount = 0
    }

    override fun getStats(): String? {
        val handle = sessionHandle.get()
        if (handle == 0L || !nativeAvailable.get()) return null
        return try {
            nativeGetStats(handle)
        } catch (e: Exception) {
            Log.d(TAG, "Could not retrieve MNN native stats: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MnnBackend"

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
                System.loadLibrary("MNN")
                Log.d(TAG, "Loaded libMNN.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libMNN.so not found: ${e.message}")
            }

            try {
                System.loadLibrary("MNN_Express")
                Log.d(TAG, "Loaded libMNN_Express.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "libMNN_Express.so not available: ${e.message}")
            }

            try {
                System.loadLibrary("mnnllm")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnnllm.so — MNN LLM inference available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnnllm.so not found — MNN LLM inference unavailable: ${e.message}")
            }
        }

        // ---- JNI native methods (implemented in llm_jni.cpp) ----

        @JvmStatic
        private external fun nativeCreateSession(
            modelDir: String, backend: Int, threads: Int, useMmap: Boolean
        ): Long

        @JvmStatic
        private external fun nativeGenerate(
            handle: Long, prompt: String, maxTokens: Int, temp: Float, topP: Float
        ): String?

        @JvmStatic
        private external fun nativeGetLastTokenCount(handle: Long): Int

        @JvmStatic
        private external fun nativeGetStats(handle: Long): String?

        @JvmStatic
        private external fun nativeDestroySession(handle: Long)
    }
}
