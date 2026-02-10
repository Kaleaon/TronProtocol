package com.tronprotocol.app.rag

/**
 * A chunk of text with metadata and embeddings
 *
 * Combines features from:
 * - landseek's TextChunk with MemRL Q-values
 * - ToolNeuron's chunk management
 */
class TextChunk(
    val chunkId: String,
    val content: String,
    val source: String,         // Source document or conversation
    val sourceType: String,     // "document", "conversation", "memory", "knowledge"
    val timestamp: String,
    val tokenCount: Int
) {
    var metadata: MutableMap<String, Any> = mutableMapOf()
    var embedding: FloatArray? = null

    // MemRL fields for self-evolving memory (arXiv:2601.03192)
    var qValue: Float = 0.5f        // Q-value for utility-based ranking (range 0-1)
        private set
    var retrievalCount: Int = 0     // Number of times retrieved
        private set
    var successCount: Int = 0       // Number of successful retrievals
        private set

    /**
     * Update Q-value based on feedback (MemRL learning)
     * @param success Whether the retrieval was successful
     * @param learningRate Learning rate for Q-value update (default 0.1)
     */
    fun updateQValue(success: Boolean, learningRate: Float) {
        retrievalCount++
        if (success) {
            successCount++
        }

        // Q-learning update: Q(s,a) += alpha * (reward - Q(s,a))
        val reward = if (success) 1.0f else 0.0f
        qValue += learningRate * (reward - qValue)

        // Clamp to [0, 1]
        qValue = qValue.coerceIn(0.0f, 1.0f)
    }

    /**
     * Get success rate for this chunk
     */
    fun getSuccessRate(): Float =
        if (retrievalCount > 0) successCount.toFloat() / retrievalCount else 0.0f

    /**
     * Add metadata field
     */
    fun addMetadata(key: String, value: Any) {
        metadata[key] = value
    }

    override fun toString(): String =
        "TextChunk{" +
                "id='" + chunkId + '\'' +
                ", source='" + source + '\'' +
                ", tokens=" + tokenCount +
                ", qValue=" + "%.3f".format(qValue) +
                ", successRate=" + "%.1f%%".format(getSuccessRate() * 100) +
                '}'
}
