package com.tronprotocol.app.guidance

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelFailoverManagerTest {

    private lateinit var apiClient: AnthropicApiClient
    private lateinit var manager: ModelFailoverManager

    @Before
    fun setUp() {
        apiClient = AnthropicApiClient(60, 0L)
        manager = ModelFailoverManager(apiClient)
    }

    // --- initially all models are healthy ---

    @Test
    fun initially_allModelsAreHealthy() {
        val health = manager.getModelHealth()
        assertFalse("Health report should not be empty", health.isEmpty())
        for ((modelId, info) in health) {
            assertTrue(
                "Model $modelId should be available initially",
                info["available"] as Boolean
            )
        }
    }

    // --- getModelHealth returns map with model info ---

    @Test
    fun getModelHealth_returnsMapWithModelInfo() {
        val health = manager.getModelHealth()
        assertTrue(health.containsKey(AnthropicApiClient.MODEL_SONNET))
        assertTrue(health.containsKey(AnthropicApiClient.MODEL_OPUS))

        val sonnetHealth = health[AnthropicApiClient.MODEL_SONNET]!!
        assertTrue(sonnetHealth.containsKey("available"))
        assertTrue(sonnetHealth.containsKey("successes"))
        assertTrue(sonnetHealth.containsKey("failures"))
        assertTrue(sonnetHealth.containsKey("cooldown_remaining_ms"))
        assertTrue(sonnetHealth.containsKey("cost_tier"))
    }

    // --- isModelHealthy (via getModelHealth available field) returns true for fresh model ---

    @Test
    fun isModelHealthy_returnsTrueForFreshModel() {
        val health = manager.getModelHealth()
        val sonnetAvailable = health[AnthropicApiClient.MODEL_SONNET]!!["available"] as Boolean
        assertTrue("Fresh model should be healthy/available", sonnetAvailable)
    }

    // --- resetCooldowns clears all cooldowns ---

    @Test
    fun resetCooldowns_clearsAllCooldowns() {
        // After reset, all models should be available
        manager.resetCooldowns()

        val health = manager.getModelHealth()
        for ((modelId, info) in health) {
            assertTrue(
                "Model $modelId should be available after reset",
                info["available"] as Boolean
            )
            assertEquals(
                "Cooldown remaining should be 0 after reset",
                0L, info["cooldown_remaining_ms"]
            )
        }
    }

    // --- health report tracks successes and failures as 0 initially ---

    @Test
    fun getModelHealth_initialSuccessesAndFailuresAreZero() {
        val health = manager.getModelHealth()
        for ((_, info) in health) {
            assertEquals(0, info["successes"])
            assertEquals(0, info["failures"])
        }
    }

    // --- executeWithFailover returns failure when API is unreachable ---

    @Test
    fun executeWithFailover_returnsFailureWhenApiUnreachable() {
        // Using a dummy API key will fail since there's no real API server
        // The test verifies the failover pipeline returns a structured FailoverResult
        val result = manager.executeWithFailover(
            apiKey = "test_dummy_key",
            prompt = "test prompt",
            maxTokens = 100
        )
        // The API call will fail (no real server) but should not throw
        assertNotNull(result)
        assertFalse("Should not succeed with dummy key and no server", result.success)
        assertNotNull(result.error)
        assertTrue(result.attemptsCount >= 0)
        assertTrue(result.totalLatencyMs >= 0)
    }

    // --- default models list includes Sonnet and Opus ---

    @Test
    fun defaultModels_includesSonnetAndOpus() {
        val defaults = ModelFailoverManager.defaultModels()
        assertEquals(2, defaults.size)
        assertTrue(defaults.any { it.modelId == AnthropicApiClient.MODEL_SONNET })
        assertTrue(defaults.any { it.modelId == AnthropicApiClient.MODEL_OPUS })
    }

    // --- ModelProfile has expected fields ---

    @Test
    fun modelProfile_hasExpectedFields() {
        val defaults = ModelFailoverManager.defaultModels()
        val sonnet = defaults.first { it.modelId == AnthropicApiClient.MODEL_SONNET }

        assertEquals("Claude Sonnet 4.5", sonnet.displayName)
        assertTrue(sonnet.maxTokens > 0)
        assertTrue(sonnet.contextWindow > 0)
        assertEquals(ModelFailoverManager.CostTier.MEDIUM, sonnet.costTier)
        assertTrue(sonnet.capabilities.isNotEmpty())
    }
}
