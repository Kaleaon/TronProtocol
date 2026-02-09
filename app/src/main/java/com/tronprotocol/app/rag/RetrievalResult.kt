package com.tronprotocol.app.rag

/**
 * Result from a RAG retrieval query
 *
 * Combines landseek and ToolNeuron retrieval patterns
 */
data class RetrievalResult(
    val chunk: TextChunk,
    val score: Float,
    val strategy: RetrievalStrategy
) {
    override fun toString(): String =
        "RetrievalResult{" +
                "score=" + "%.3f".format(score) +
                ", strategy=" + strategy +
                ", chunk=" + chunk +
                '}'
}
