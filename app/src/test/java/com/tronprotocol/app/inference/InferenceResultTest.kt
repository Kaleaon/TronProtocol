package com.tronprotocol.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceResultTest {

    @Test
    fun testLocalResult() {
        val result = InferenceResult(
            text = "Hello world",
            tier = InferenceTier.LOCAL_ON_DEMAND,
            modelId = "smollm2-1.7b",
            latencyMs = 3500,
            tokenCount = 2
        )
        assertTrue(result.isLocalResult)
        assertFalse(result.isCloudResult)
        assertEquals("Hello world", result.text)
        assertEquals(InferenceTier.LOCAL_ON_DEMAND, result.tier)
    }

    @Test
    fun testCloudResult() {
        val result = InferenceResult(
            text = "Complex answer",
            tier = InferenceTier.CLOUD_FALLBACK,
            modelId = "claude-sonnet-4-5-20250929",
            latencyMs = 2000,
            tokenCount = 50
        )
        assertTrue(result.isCloudResult)
        assertFalse(result.isLocalResult)
    }

    @Test
    fun testErrorResult() {
        val result = InferenceResult.error(
            InferenceTier.LOCAL_ON_DEMAND,
            "Model not loaded",
            150
        )
        assertEquals("", result.text)
        assertEquals("error", result.modelId)
        assertEquals(0.0f, result.confidence, 0.001f)
        assertEquals(150L, result.latencyMs)
    }

    @Test
    fun testJsonSerialization() {
        val result = InferenceResult(
            text = "test",
            tier = InferenceTier.LOCAL_ALWAYS_ON,
            modelId = "lightweight",
            latencyMs = 50
        )
        val json = result.toJson()
        assertEquals("test", json.getString("text"))
        assertEquals("local_always_on", json.getString("tier"))
        assertEquals("lightweight", json.getString("model_id"))
        assertEquals(50, json.getLong("latency_ms"))
    }

    @Test
    fun testInferenceTierLabels() {
        assertEquals("local_always_on", InferenceTier.LOCAL_ALWAYS_ON.label)
        assertEquals("local_on_demand", InferenceTier.LOCAL_ON_DEMAND.label)
        assertEquals("cloud_fallback", InferenceTier.CLOUD_FALLBACK.label)
    }

    @Test
    fun testInferenceTierPriorities() {
        assertTrue(InferenceTier.LOCAL_ALWAYS_ON.priority < InferenceTier.LOCAL_ON_DEMAND.priority)
        assertTrue(InferenceTier.LOCAL_ON_DEMAND.priority < InferenceTier.CLOUD_FALLBACK.priority)
    }
}
