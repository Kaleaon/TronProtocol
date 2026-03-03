package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RAGStoreStrategyRoutingTest {

    private lateinit var store: RAGStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = RAGStore(context, "routing_${System.nanoTime()}")
        store.addKnowledge("Paris is the capital of France", "geography")
        store.addMemory("I prefer coffee in the morning", 0.9f)
        store.addKnowledge("To reset password open settings and tap reset", "procedure")
    }

    @Test
    fun eachStrategy_returnsDeterministicBoundedResults() {
        RetrievalStrategy.values().forEach { strategy ->
            val results = store.retrieve("reset password france coffee", strategy, 2)
            assertTrue(results.size <= 2)
            if (results.size > 1) {
                assertTrue(results[0].score >= results[1].score)
            }
        }
    }

    @Test
    fun mixedStrategyRouting_usesIntentPlanAndTagsStrategyIds() {
        val results = store.retrieve("how do i reset password", RetrievalIntentType.PROCEDURAL_TASK, 3)
        assertFalse(results.isEmpty())
        assertTrue(results.all { it.strategyId.startsWith("PROCEDURAL_TASK:") })
        assertTrue(results.size <= 3)
    }
}
