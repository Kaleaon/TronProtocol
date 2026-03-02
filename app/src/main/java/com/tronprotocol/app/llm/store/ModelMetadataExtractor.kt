package com.tronprotocol.app.llm.store

/**
 * Utility for extracting model metadata from filenames and model names.
 *
 * Ported from ToolNeuron's ModelMetadataExtractor. Provides regex-based extraction
 * of parameter count, quantization type, and size classification from model
 * file names following common HuggingFace naming conventions.
 */
object ModelMetadataExtractor {

    private val PARAM_COUNT_REGEX = Regex("""(\d+\.?\d*)\s*[Bb]""")
    private val QUANT_REGEX = Regex("""[Qq](\d+)(?:_([Kk](?:_[SMsmLl])?))?""")
    private val QUANT_FULL_REGEX = Regex("""(Q\d+_K(?:_[SML])?|Q\d+_0|Q\d+_1|IQ\d+_[A-Z]+|F16|F32|BF16)""", RegexOption.IGNORE_CASE)

    enum class SizeCategory { TINY, SMALL, MEDIUM, LARGE, XLARGE }

    /**
     * Extract parameter count string from a model name.
     * E.g. "Qwen2.5-1.5B-Instruct" → "1.5B"
     */
    fun extractParameterCount(name: String): String? {
        return PARAM_COUNT_REGEX.find(name)?.let { "${it.groupValues[1]}B" }
    }

    /**
     * Extract quantization type from a filename.
     * E.g. "Qwen3-8B-Q4_K_M.gguf" → "Q4_K_M"
     */
    fun extractQuantization(fileName: String): String? {
        return QUANT_FULL_REGEX.find(fileName)?.groupValues?.get(1)?.uppercase()
    }

    /**
     * Classify model size based on parameter count string.
     */
    fun extractSizeCategory(paramStr: String?): SizeCategory {
        if (paramStr == null) return SizeCategory.MEDIUM
        val numStr = paramStr.replace(Regex("[^0-9.]"), "")
        val num = numStr.toDoubleOrNull() ?: return SizeCategory.MEDIUM
        return when {
            num < 1.0 -> SizeCategory.TINY
            num < 2.0 -> SizeCategory.SMALL
            num < 5.0 -> SizeCategory.MEDIUM
            num < 10.0 -> SizeCategory.LARGE
            else -> SizeCategory.XLARGE
        }
    }

    /**
     * Parse a human-readable size string to bytes.
     * E.g. "500 MB" → 524_288_000, "1.2 GB" → 1_288_490_189
     */
    fun parseSizeToBytes(sizeStr: String): Long {
        val parts = sizeStr.trim().split(Regex("\\s+"))
        if (parts.size != 2) return 0
        val value = parts[0].toDoubleOrNull() ?: return 0
        return when (parts[1].uppercase()) {
            "B", "BYTES" -> value.toLong()
            "KB" -> (value * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "TB" -> (value * 1024 * 1024 * 1024 * 1024).toLong()
            else -> 0
        }
    }

    /**
     * Format bytes into a human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * Detect if a file is likely a GGUF model based on its name.
     */
    fun isGgufFile(fileName: String): Boolean =
        fileName.lowercase().endsWith(".gguf")

    /**
     * Detect if a model supports tool calling based on name heuristics.
     * Models with "tool", "function", "claude-code", or certain Qwen variants
     * are likely tool-calling capable.
     */
    fun isLikelyToolCallingModel(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("tool") ||
                lower.contains("function") ||
                lower.contains("claude-code") ||
                (lower.contains("qwen") && lower.contains("instruct"))
    }
}
