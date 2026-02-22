package com.tronprotocol.app.inference

import org.json.JSONObject

/**
 * Result of an inference request routed through [InferenceRouter].
 */
data class InferenceResult(
    val text: String,
    val tier: InferenceTier,
    val modelId: String,
    val latencyMs: Long,
    val tokenCount: Int = 0,
    val confidence: Float = 1.0f,
    val metadata: Map<String, Any> = emptyMap()
) {
    val isCloudResult: Boolean get() = tier == InferenceTier.CLOUD_FALLBACK
    val isLocalResult: Boolean get() = tier != InferenceTier.CLOUD_FALLBACK

    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("tier", tier.label)
        put("model_id", modelId)
        put("latency_ms", latencyMs)
        put("token_count", tokenCount)
        put("confidence", confidence.toDouble())
    }

    companion object {
        fun error(tier: InferenceTier, message: String, latencyMs: Long): InferenceResult =
            InferenceResult(
                text = "",
                tier = tier,
                modelId = "error",
                latencyMs = latencyMs,
                confidence = 0.0f,
                metadata = mapOf("error" to message)
            )
    }
}
