package com.tronprotocol.app.llm.backend

import android.util.Log
import java.io.File

/**
 * Selects the appropriate LLM backend based on model format, device capabilities,
 * and user preference.
 *
 * Supports two backends:
 * - **MNN**: Alibaba's Mobile Neural Network framework (`.mnn` models)
 * - **GGUF**: llama.cpp-based inference (`.gguf` models)
 *
 * The selector auto-detects model format from file extension and falls back
 * to the preferred backend when multiple formats are supported.
 */
class BackendSelector(
    private val mnnBackend: MnnBackend = MnnBackend(),
    private val ggufBackend: GgufBackend = GgufBackend()
) {

    /** The user's preferred backend when format is ambiguous. */
    var preferredBackend: BackendType = BackendType.GGUF

    /**
     * Select the best backend for the given model path.
     *
     * @param modelPath Path to the model directory (MNN) or file (GGUF)
     * @param preferred Override backend preference for this call
     * @return The selected backend, or null if none can handle the model
     */
    fun selectForModel(modelPath: String, preferred: BackendType? = null): LLMBackend? {
        val detected = detectFormat(modelPath)
        val preference = preferred ?: preferredBackend

        Log.d(TAG, "Selecting backend for $modelPath (detected=$detected, preferred=$preference)")

        return when (detected) {
            BackendType.GGUF -> {
                if (ggufBackend.isAvailable) {
                    Log.d(TAG, "Selected GGUF backend for .gguf model")
                    ggufBackend
                } else {
                    Log.w(TAG, "GGUF model detected but backend unavailable")
                    null
                }
            }
            BackendType.MNN -> {
                if (mnnBackend.isAvailable) {
                    Log.d(TAG, "Selected MNN backend for .mnn model")
                    mnnBackend
                } else {
                    Log.w(TAG, "MNN model detected but backend unavailable")
                    null
                }
            }
            else -> {
                // Unknown format — try preferred backend first
                val primary = getBackend(preference)
                if (primary.isAvailable) {
                    Log.d(TAG, "Unknown format, using preferred backend: ${primary.name}")
                    primary
                } else {
                    // Fall back to whatever is available
                    val fallback = getAvailableBackends().firstOrNull()
                    if (fallback != null) {
                        Log.d(TAG, "Unknown format, falling back to: ${fallback.name}")
                    } else {
                        Log.e(TAG, "No backends available")
                    }
                    fallback
                }
            }
        }
    }

    /**
     * Get a specific backend by type.
     */
    fun getBackend(type: BackendType): LLMBackend = when (type) {
        BackendType.MNN -> mnnBackend
        BackendType.GGUF -> ggufBackend
    }

    /**
     * Get all backends that have their native libraries loaded.
     */
    fun getAvailableBackends(): List<LLMBackend> {
        return listOfNotNull(
            mnnBackend.takeIf { it.isAvailable },
            ggufBackend.takeIf { it.isAvailable }
        )
    }

    /**
     * Get all registered backends regardless of availability.
     */
    fun getAllBackends(): List<LLMBackend> = listOf(mnnBackend, ggufBackend)

    /**
     * Check if at least one backend is available.
     */
    fun hasAvailableBackend(): Boolean =
        mnnBackend.isAvailable || ggufBackend.isAvailable

    /**
     * Detect model format from file/directory structure.
     *
     * - If path ends with `.gguf` → GGUF
     * - If path is a directory containing `llm.mnn` → MNN
     * - If path is a directory containing any `.gguf` file → GGUF
     * - Otherwise → null (unknown)
     */
    fun detectFormat(modelPath: String): BackendType? {
        val path = modelPath.trimEnd('/')

        // Direct .gguf file
        if (path.lowercase().endsWith(".gguf")) {
            return BackendType.GGUF
        }

        val dir = File(path)
        if (!dir.exists()) return null

        if (dir.isFile) {
            // Single file — check extension
            return when {
                dir.name.lowercase().endsWith(".gguf") -> BackendType.GGUF
                dir.name.lowercase().endsWith(".mnn") -> BackendType.MNN
                else -> null
            }
        }

        if (dir.isDirectory) {
            // Check for MNN artifacts
            if (File(dir, "llm.mnn").exists()) {
                return BackendType.MNN
            }

            // Check for any GGUF file
            val ggufFiles = dir.listFiles { _, name -> name.lowercase().endsWith(".gguf") }
            if (ggufFiles != null && ggufFiles.isNotEmpty()) {
                return BackendType.GGUF
            }
        }

        return null
    }

    companion object {
        private const val TAG = "BackendSelector"
    }
}

/**
 * Supported LLM inference backend types.
 */
enum class BackendType(val displayName: String, val fileExtension: String) {
    MNN("MNN (Mobile Neural Network)", ".mnn"),
    GGUF("GGUF (llama.cpp)", ".gguf");

    companion object {
        fun fromString(value: String): BackendType? = when (value.lowercase()) {
            "mnn" -> MNN
            "gguf", "llama", "llama.cpp" -> GGUF
            else -> null
        }
    }
}
