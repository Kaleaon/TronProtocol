package com.tronprotocol.app.rag

/**
 * Result from a RAG retrieval query
 *
 * Combines landseek and ToolNeuron retrieval patterns
 */
data class RetrievalResult(
    val chunk: TextChunk,
    val score: Float,
    val strategy: RetrievalStrategy,
    val strategyId: String = strategy.name,
    val scoreDistribution: ScoreDistribution? = null,
    val stageSource: String? = null
) {
    override fun toString(): String =
        "RetrievalResult{" +
                "score=" + "%.3f".format(score) +
                ", strategy=" + strategy +
                ", strategyId=" + strategyId +
                ", stageSource=" + stageSource +
                ", chunk=" + chunk +
                '}'
}

data class ScoreDistribution(
    val min: Float,
    val max: Float,
    val mean: Float,
    val stdDev: Float
)
