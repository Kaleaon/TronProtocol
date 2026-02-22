package com.tronprotocol.app.models

import kotlin.math.ln
import kotlin.math.pow

/**
 * Represents an AI model that can be loaded and used for inference
 *
 * Inspired by ToolNeuron's model management system
 */
class AIModel(
    var id: String,
    var name: String,
    var path: String,
    var modelType: String,  // e.g., "GGUF", "TFLite"
    var size: Long           // Size in bytes
) {
    var isLoaded: Boolean = false
    var category: String = "General" // e.g., "General", "Medical", "Coding"

    override fun toString(): String {
        return "AIModel{" +
                "id='$id'" +
                ", name='$name'" +
                ", modelType='$modelType'" +
                ", size=${formatSize(size)}" +
                ", isLoaded=$isLoaded" +
                ", category='$category'" +
                "}"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, 6)
        val pre = "${"KMGTPE"[exp - 1]}B"
        return String.format("%.1f %s", bytes / 1024.0.pow(exp.toDouble()), pre)
    }
}
