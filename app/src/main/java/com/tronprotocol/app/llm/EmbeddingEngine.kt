package com.tronprotocol.app.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * On-device semantic embedding engine using a quantized GGUF model.
 *
 * Ported from ToolNeuron's EmbeddingEngine. Uses a small, quantized
 * all-MiniLM-L6-v2 model (Q5_K_M, ~25MB, 384 dimensions) for generating
 * text embeddings without cloud API calls.
 *
 * Thread-safe via Mutex, supports batched embedding, and auto-detects
 * embedding dimension from the model.
 *
 * Requires the GGUF backend native libraries to be available.
 */
class EmbeddingEngine(private val context: Context) {

    private val mutex = Mutex()
    private var modelLoaded = false
    private var embeddingDimension = DEFAULT_DIMENSION

    /** Whether the embedding model is loaded and ready. */
    fun isReady(): Boolean = modelLoaded

    /** The dimensionality of the embedding vectors. */
    fun getDimension(): Int = embeddingDimension

    /**
     * Load the embedding model.
     *
     * @param modelPath Path to the GGUF embedding model file
     * @return true if loaded successfully
     */
    suspend fun loadModel(modelPath: String): Boolean = mutex.withLock {
        if (modelLoaded) {
            Log.d(TAG, "Embedding model already loaded")
            return@withLock true
        }

        if (!nativeAvailable) {
            Log.w(TAG, "Embedding native library not available")
            return@withLock false
        }

        val file = File(modelPath)
        if (!file.exists()) {
            Log.w(TAG, "Embedding model not found: $modelPath")
            return@withLock false
        }

        return@withLock try {
            nativeLoadEmbeddingModel(modelPath)
            embeddingDimension = nativeGetDimension()
            modelLoaded = true
            Log.d(TAG, "Embedding model loaded: ${file.name} (dim=$embeddingDimension)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load embedding model: ${e.message}", e)
            false
        }
    }

    /**
     * Generate an embedding vector for the given text.
     *
     * @param text Input text to embed
     * @return Float array of dimension [embeddingDimension], or null on failure
     */
    suspend fun embed(text: String): FloatArray? {
        if (!modelLoaded) {
            Log.w(TAG, "Embedding model not loaded")
            return null
        }

        return try {
            withTimeout(TIMEOUT_MS) {
                mutex.withLock {
                    nativeEmbed(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed: ${e.message}", e)
            null
        }
    }

    /**
     * Generate embeddings for a batch of texts.
     *
     * @param texts List of texts to embed
     * @return List of embedding vectors (null entries for failures)
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
        if (!modelLoaded) {
            Log.w(TAG, "Embedding model not loaded")
            return texts.map { null }
        }

        return try {
            withTimeout(TIMEOUT_MS * texts.size) {
                mutex.withLock {
                    texts.map { text ->
                        try {
                            nativeEmbed(text)
                        } catch (e: Exception) {
                            Log.w(TAG, "Batch embed failed for text: ${e.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch embedding timed out: ${e.message}", e)
            texts.map { null }
        }
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same dimension" }
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 0) (dotProduct / denom.toFloat()) else 0f
    }

    /**
     * Unload the embedding model and free resources.
     */
    suspend fun unload() = mutex.withLock {
        if (modelLoaded) {
            try {
                nativeUnloadEmbeddingModel()
            } catch (_: Exception) { }
            modelLoaded = false
            Log.d(TAG, "Embedding model unloaded")
        }
    }

    /**
     * Get the default model path in the app's files directory.
     */
    fun getDefaultModelPath(): String {
        return File(context.filesDir, "embedding_models/$DEFAULT_MODEL_NAME").absolutePath
    }

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val DEFAULT_DIMENSION = 384
        private const val DEFAULT_MODEL_NAME = "all-MiniLM-L6-v2-Q5_K_M.gguf"
        private const val TIMEOUT_MS = 15_000L

        private var nativeAvailable = false

        init {
            try {
                // Reuse the GGUF backend library which includes embedding support
                System.loadLibrary("ai_gguf")
                nativeAvailable = true
            } catch (_: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("llama")
                    nativeAvailable = true
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "Embedding native library not available")
                }
            }
        }

        @JvmStatic
        private external fun nativeLoadEmbeddingModel(modelPath: String)

        @JvmStatic
        private external fun nativeEmbed(text: String): FloatArray

        @JvmStatic
        private external fun nativeGetDimension(): Int

        @JvmStatic
        private external fun nativeUnloadEmbeddingModel()
    }
}
