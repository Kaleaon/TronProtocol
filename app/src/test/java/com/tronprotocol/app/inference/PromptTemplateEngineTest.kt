package com.tronprotocol.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptTemplateEngineTest {

    private lateinit var engine: PromptTemplateEngine

    @Before
    fun setUp() {
        engine = PromptTemplateEngine()
    }

    // ======== Query Classification Tests ========

    @Test
    fun testClassifyCodeQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.CODE,
            engine.classifyQuery("Write a function to sort an array")
        )
    }

    @Test
    fun testClassifyCodeQueryDebug() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.CODE,
            engine.classifyQuery("Debug this error in my Python script")
        )
    }

    @Test
    fun testClassifyDeviceControlQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.DEVICE_CONTROL,
            engine.classifyQuery("Send SMS to John")
        )
    }

    @Test
    fun testClassifySummarizationQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.SUMMARIZATION,
            engine.classifyQuery("Summarize our conversation so far")
        )
    }

    @Test
    fun testClassifyAnalysisQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.ANALYSIS,
            engine.classifyQuery("Compare the pros and cons of React vs Vue")
        )
    }

    @Test
    fun testClassifyCreativeQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.CREATIVE,
            engine.classifyQuery("Write a story about a dragon")
        )
    }

    @Test
    fun testClassifyFactualQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.FACTUAL,
            engine.classifyQuery("What is the capital of France?")
        )
    }

    @Test
    fun testClassifyConversationalQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.CONVERSATION,
            engine.classifyQuery("Hello, how are you?")
        )
    }

    @Test
    fun testClassifyGeneralQuery() {
        assertEquals(
            PromptTemplateEngine.QueryCategory.GENERAL,
            engine.classifyQuery("Tell me about your capabilities")
        )
    }

    // ======== Template Tests ========

    @Test
    fun testGetTemplateForAllCategories() {
        for (category in PromptTemplateEngine.QueryCategory.entries) {
            val template = engine.getTemplate(category)
            assertNotNull("Template should exist for $category", template)
            assertTrue(
                "System prefix should be non-empty for $category",
                template.systemPrefix.isNotBlank()
            )
            assertTrue(
                "Recommended max tokens should be positive for $category",
                template.recommendedMaxTokens > 0
            )
        }
    }

    @Test
    fun testDeviceControlTemplateIncludesDeviceState() {
        val template = engine.getTemplate(PromptTemplateEngine.QueryCategory.DEVICE_CONTROL)
        assertTrue(template.includeDeviceState)
    }

    // ======== Prompt Construction Tests ========

    @Test
    fun testConstructPromptBasic() {
        val result = engine.constructPrompt(
            userQuery = "Hello"
        )
        assertNotNull(result)
        assertTrue(result.fullPrompt.contains("Hello"))
        assertTrue(result.estimatedTokens > 0)
    }

    @Test
    fun testConstructPromptWithRagContext() {
        val result = engine.constructPrompt(
            userQuery = "What did we discuss yesterday?",
            ragContext = "Yesterday the user asked about weather."
        )
        assertTrue(result.contextIncluded)
        assertTrue(result.fullPrompt.contains("Relevant Memory"))
        assertTrue(result.fullPrompt.contains("yesterday"))
    }

    @Test
    fun testConstructPromptWithConversationContext() {
        val result = engine.constructPrompt(
            userQuery = "Continue where we left off",
            conversationContext = "Previous: user asked about Kotlin coroutines"
        )
        assertTrue(result.contextIncluded)
        assertTrue(result.fullPrompt.contains("Kotlin coroutines"))
    }

    @Test
    fun testConstructPromptWithDeviceState() {
        val result = engine.constructPrompt(
            userQuery = "Turn off wifi",
            deviceState = "WiFi: ON, Bluetooth: OFF"
        )
        assertEquals(PromptTemplateEngine.QueryCategory.DEVICE_CONTROL, result.category)
        assertTrue(result.fullPrompt.contains("WiFi: ON"))
    }

    @Test
    fun testConstructPromptLocalTierHint() {
        val result = engine.constructPrompt(
            userQuery = "Hello",
            tier = InferenceTier.LOCAL_ON_DEMAND
        )
        assertTrue(result.fullPrompt.contains("on-device"))
    }

    // ======== Token Recommendation Tests ========

    @Test
    fun testRecommendedMaxTokensLocalLower() {
        val category = PromptTemplateEngine.QueryCategory.GENERAL
        val localTokens = engine.getRecommendedMaxTokens(category, InferenceTier.LOCAL_ALWAYS_ON)
        val onDemandTokens = engine.getRecommendedMaxTokens(category, InferenceTier.LOCAL_ON_DEMAND)
        assertTrue("LOCAL_ALWAYS_ON should have fewer tokens", localTokens < onDemandTokens)
    }

    @Test
    fun testRecommendedMaxTokensCloudHigher() {
        val category = PromptTemplateEngine.QueryCategory.GENERAL
        val onDemandTokens = engine.getRecommendedMaxTokens(category, InferenceTier.LOCAL_ON_DEMAND)
        val cloudTokens = engine.getRecommendedMaxTokens(category, InferenceTier.CLOUD_FALLBACK)
        assertTrue("CLOUD should have more tokens", cloudTokens > onDemandTokens)
    }

    @Test
    fun testCreativeHasHigherTokenBudget() {
        val creativeTokens = engine.getRecommendedMaxTokens(
            PromptTemplateEngine.QueryCategory.CREATIVE,
            InferenceTier.LOCAL_ON_DEMAND
        )
        val deviceControlTokens = engine.getRecommendedMaxTokens(
            PromptTemplateEngine.QueryCategory.DEVICE_CONTROL,
            InferenceTier.LOCAL_ON_DEMAND
        )
        assertTrue("Creative should have more tokens than device control",
            creativeTokens > deviceControlTokens)
    }
}
