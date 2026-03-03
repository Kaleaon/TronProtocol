package com.tronprotocol.app.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryPlannerTest {

    private val planner = IntentTypeQueryPlanner()

    @Test
    fun plan_factLookup_routesToFactStrategies() {
        val plan = planner.plan("What is the capital of Japan?", RetrievalIntentType.FACT_LOOKUP)
        assertEquals(
            listOf(RetrievalStrategy.GRAPH, RetrievalStrategy.HYBRID, RetrievalStrategy.SEMANTIC),
            plan.strategies
        )
    }

    @Test
    fun plan_personalMemory_routesToMemoryStrategies() {
        val plan = planner.plan("What did I do yesterday?", RetrievalIntentType.PERSONAL_MEMORY)
        assertEquals(
            listOf(RetrievalStrategy.MEMRL, RetrievalStrategy.NTS_CASCADE, RetrievalStrategy.RECENCY),
            plan.strategies
        )
    }

    @Test
    fun plan_proceduralTask_routesToTaskStrategies() {
        val plan = planner.plan("How do I reset my password?", RetrievalIntentType.PROCEDURAL_TASK)
        assertEquals(
            listOf(RetrievalStrategy.KEYWORD, RetrievalStrategy.HYBRID, RetrievalStrategy.SEMANTIC),
            plan.strategies
        )
    }
}
