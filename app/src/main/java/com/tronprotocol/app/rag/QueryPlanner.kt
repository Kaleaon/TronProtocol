package com.tronprotocol.app.rag

enum class RetrievalIntentType {
    FACT_LOOKUP,
    PERSONAL_MEMORY,
    PROCEDURAL_TASK
}

data class RetrievalPlan(
    val intentType: RetrievalIntentType,
    val strategies: List<RetrievalStrategy>
)

interface QueryPlanner {
    fun plan(query: String, intentType: RetrievalIntentType): RetrievalPlan
}

class IntentTypeQueryPlanner : QueryPlanner {
    override fun plan(query: String, intentType: RetrievalIntentType): RetrievalPlan {
        val strategies = when (intentType) {
            RetrievalIntentType.FACT_LOOKUP -> listOf(
                RetrievalStrategy.GRAPH,
                RetrievalStrategy.HYBRID,
                RetrievalStrategy.SEMANTIC
            )
            RetrievalIntentType.PERSONAL_MEMORY -> listOf(
                RetrievalStrategy.MEMRL,
                RetrievalStrategy.NTS_CASCADE,
                RetrievalStrategy.RECENCY
            )
            RetrievalIntentType.PROCEDURAL_TASK -> listOf(
                RetrievalStrategy.KEYWORD,
                RetrievalStrategy.HYBRID,
                RetrievalStrategy.SEMANTIC
            )
        }
        return RetrievalPlan(intentType, strategies)
    }
}
