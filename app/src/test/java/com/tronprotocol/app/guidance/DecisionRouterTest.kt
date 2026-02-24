package com.tronprotocol.app.guidance

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecisionRouterTest {

    private lateinit var router: DecisionRouter

    @Before
    fun setUp() {
        router = DecisionRouter()
    }

    // --- simple greetings route to local ---

    @Test
    fun decide_simpleGreeting_routesToLocal() {
        val decision = router.decide("hello")
        assertTrue("Simple greeting 'hello' should route locally", decision.useLocal)
        assertFalse(decision.useOnDeviceLLM)
        assertFalse(decision.useHereticModel)
        assertNull(decision.cloudModel)
    }

    @Test
    fun decide_statusQuery_routesToLocal() {
        val decision = router.decide("status")
        assertTrue("Simple 'status' should route locally", decision.useLocal)
    }

    @Test
    fun decide_helpQuery_routesToLocal() {
        val decision = router.decide("help")
        assertTrue("Simple 'help' should route locally", decision.useLocal)
    }

    // --- complex prompts don't route to local ---

    @Test
    fun decide_complexPrompt_doesNotRouteToLocal() {
        val complexPrompt = "Please analyze the performance implications of using coroutines " +
                "versus threads in a multi-module Android application with dependency injection " +
                "and explain the tradeoffs for each approach in detail with code examples"
        val decision = router.decide(complexPrompt)
        assertFalse(
            "Complex prompt should not route locally",
            decision.useLocal
        )
    }

    // --- high-stakes terms route to cloud (Opus) ---

    @Test
    fun decide_identityTerm_routesToCloud() {
        val decision = router.decide("What is your identity and who are you?")
        assertFalse(decision.useLocal)
        assertFalse(decision.useOnDeviceLLM)
        assertNotNull("Identity query should route to cloud", decision.cloudModel)
        assertEquals(AnthropicApiClient.MODEL_OPUS, decision.cloudModel)
    }

    @Test
    fun decide_ethicsTerm_routesToCloud() {
        val decision = router.decide("Let's discuss the ethics of AI surveillance")
        assertNotNull("Ethics query should route to cloud", decision.cloudModel)
        assertEquals(AnthropicApiClient.MODEL_OPUS, decision.cloudModel)
    }

    @Test
    fun decide_selfModTerm_routesToCloud() {
        val decision = router.decide("Perform a self-mod to update the configuration")
        assertNotNull("Self-mod query should route to cloud", decision.cloudModel)
        assertEquals(AnthropicApiClient.MODEL_OPUS, decision.cloudModel)
    }

    @Test
    fun decide_policyOverride_routesToCloud() {
        val decision = router.decide("I need a policy override for this action")
        assertNotNull("Policy override should route to cloud", decision.cloudModel)
    }

    // --- medium-complexity prompts route to on-device LLM or cloud ---

    @Test
    fun decide_mediumComplexity_routesToOnDeviceLLMWhenAvailable() {
        router.onDeviceLLMAvailable = true
        val decision = router.decide("Explain how Kotlin coroutines work in Android development")
        assertTrue(
            "Medium-complexity with on-device LLM available should use on-device LLM",
            decision.useOnDeviceLLM
        )
        assertFalse(decision.useLocal)
    }

    @Test
    fun decide_mediumComplexity_routesToCloudWhenLLMUnavailable() {
        router.onDeviceLLMAvailable = false
        val decision = router.decide("Explain how Kotlin coroutines work in Android development")
        assertFalse(decision.useLocal)
        assertFalse(decision.useOnDeviceLLM)
        assertNotNull("Should route to cloud when LLM unavailable", decision.cloudModel)
    }

    // --- empty/null prompt handles gracefully ---

    @Test
    fun decide_nullPrompt_handlesGracefully() {
        val decision = router.decide(null)
        assertTrue("Null prompt should route locally", decision.useLocal)
    }

    @Test
    fun decide_emptyPrompt_handlesGracefully() {
        // Empty string is short and doesn't match any simple intent keywords
        // so it will route to cloud (not local since it lacks simple intent keywords)
        val decision = router.decide("")
        assertNotNull("Empty prompt should produce a valid decision", decision)
    }

    // --- RouteDecision has expected properties ---

    @Test
    fun routeDecision_hasReasonField() {
        val decision = router.decide("hello")
        assertNotNull(decision.reason)
        assertTrue(decision.reason.isNotEmpty())
    }
}
