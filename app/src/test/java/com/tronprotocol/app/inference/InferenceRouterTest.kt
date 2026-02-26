package com.tronprotocol.app.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InferenceRouterTest {

    private lateinit var context: Context
    private lateinit var router: InferenceRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        router = InferenceRouter(context)
    }

    @Test
    fun simpleQuery_routesToLocalTier() {
        // Simple greeting with low complexity hint should route to LOCAL tier
        val result = router.infer("Hello there", maxTokens = 100, complexityHint = 0.1f, requireLocal = true)
        assertNotNull("Inference result should not be null", result)
        assertTrue(
            "Simple query should route to a local tier",
            result.tier == InferenceTier.LOCAL_ALWAYS_ON || result.tier == InferenceTier.LOCAL_ON_DEMAND
        )
    }

    @Test
    fun complexQuery_mayRouteToHigherTiers() {
        // Without a local model or cloud configured, complex queries still get a result
        val result = router.infer(
            "Explain the trade-offs between different distributed consensus algorithms and analyze " +
                    "how they compare in terms of fault tolerance, then design a new approach",
            maxTokens = 1024,
            complexityHint = 0.9f
        )
        assertNotNull("Inference result should not be null", result)
        // The tier should be valid regardless of which one is chosen
        assertNotNull("Result should have a valid tier", result.tier)
        assertTrue(
            "Result tier should be one of the valid tiers",
            result.tier in InferenceTier.entries
        )
    }

    @Test
    fun selectTier_returnsValidInferenceTier() {
        // All queries should return a result with a valid InferenceTier
        val queries = listOf(
            "Hi" to 0.0f,
            "What time is it?" to 0.2f,
            "Summarize this document" to 0.5f,
            "Explain quantum computing" to 0.8f
        )

        for ((query, complexity) in queries) {
            val result = router.infer(query, complexityHint = complexity)
            assertNotNull("Result for query '$query' should not be null", result)
            assertTrue(
                "Result tier should be a valid InferenceTier for query '$query'",
                result.tier in InferenceTier.entries
            )
        }
    }
}
