package com.tronprotocol.app.swarm

import android.util.Log
import com.tronprotocol.app.guidance.AnthropicApiClient
import com.tronprotocol.app.guidance.DecisionRouter
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.plugins.PicoClawBridgePlugin
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import org.json.JSONObject
import java.util.Locale

/**
 * Swarm-aware inference router — extends the existing DecisionRouter with a
 * PicoClaw edge tier, creating a 5-level inference hierarchy:
 *
 * Tier 0: Local template — trivial prompts answered from templates
 * Tier 1: On-device MNN LLM — Qwen/Gemma up to 4B params, full privacy
 * Tier 2: PicoClaw edge — routed via swarm nodes to cloud LLMs (Groq/DeepSeek/etc.)
 * Tier 3: Cloud Sonnet — Anthropic Claude Sonnet for general guidance
 * Tier 4: Cloud Opus — Anthropic Claude Opus for high-stakes reasoning
 *
 * The edge tier (Tier 2) provides a middle ground: faster and cheaper than
 * direct Anthropic calls, with access to multiple providers via PicoClaw's
 * multi-provider support (Groq, DeepSeek, OpenRouter, Google Gemini).
 */
class SwarmInferenceRouter(
    private val baseRouter: DecisionRouter,
    private val bridge: PicoClawBridgePlugin,
    private val onDeviceLLMManager: OnDeviceLLMManager?
) {
    companion object {
        private const val TAG = "SwarmInferenceRouter"

        private val EDGE_SUITABLE_INTENTS = setOf(
            "explain", "what is", "how does", "describe", "tell me",
            "summarize", "translate", "define", "compare", "list",
            "write", "draft", "suggest", "recommend", "answer",
            "calculate", "convert", "format", "generate",
            "search", "find", "look up", "check"
        )

        private val CLOUD_ONLY_TERMS = setOf(
            "identity", "persona", "self-mod", "ethical kernel", "ethics",
            "policy override", "approval", "governance",
            "modify code", "patch", "rewrite", "approve",
            "complex analysis", "legal", "medical advice"
        )
    }

    data class SwarmRouteDecision(
        val tier: InferenceTier,
        val reason: String,
        val targetNodeId: String? = null,
        val suggestedModel: String? = null
    ) {
        enum class InferenceTier {
            LOCAL_TEMPLATE,
            ON_DEVICE_LLM,
            PICOCLAW_EDGE,
            CLOUD_SONNET,
            CLOUD_OPUS
        }
    }

    fun route(prompt: String?): SwarmRouteDecision {
        if (prompt == null || prompt.isBlank()) {
            return SwarmRouteDecision(
                SwarmRouteDecision.InferenceTier.LOCAL_TEMPLATE,
                "Empty prompt handled locally"
            )
        }

        val lowered = prompt.lowercase(Locale.US)

        // High-stakes -> always Cloud Opus
        for (term in CLOUD_ONLY_TERMS) {
            if (lowered.contains(term)) {
                return SwarmRouteDecision(
                    SwarmRouteDecision.InferenceTier.CLOUD_OPUS,
                    "High-stakes term '$term' requires Opus",
                    suggestedModel = AnthropicApiClient.MODEL_OPUS
                )
            }
        }

        // Trivial -> local template
        if (isTrivialPrompt(lowered)) {
            return SwarmRouteDecision(
                SwarmRouteDecision.InferenceTier.LOCAL_TEMPLATE,
                "Simple prompt handled locally"
            )
        }

        // On-device LLM for short/medium prompts if available
        if (onDeviceLLMManager?.isReady == true && isOnDeviceSuitable(lowered)) {
            return SwarmRouteDecision(
                SwarmRouteDecision.InferenceTier.ON_DEVICE_LLM,
                "Prompt routed to on-device MNN LLM"
            )
        }

        // PicoClaw edge tier — if nodes available and prompt is suitable
        val edgeNode = selectEdgeNode(lowered)
        if (edgeNode != null) {
            return SwarmRouteDecision(
                SwarmRouteDecision.InferenceTier.PICOCLAW_EDGE,
                "Routed to PicoClaw edge node '${edgeNode.displayName}' (${edgeNode.latencyMs}ms latency)",
                targetNodeId = edgeNode.nodeId,
                suggestedModel = edgeNode.activeModel
            )
        }

        // Default to Cloud Sonnet
        return SwarmRouteDecision(
            SwarmRouteDecision.InferenceTier.CLOUD_SONNET,
            "General guidance delegated to Cloud Sonnet",
            suggestedModel = AnthropicApiClient.MODEL_SONNET
        )
    }

    /**
     * Execute inference end-to-end using the swarm routing decision.
     * Returns the response text or null on failure.
     */
    fun executeInference(
        prompt: String,
        apiKey: String?,
        apiClient: AnthropicApiClient?
    ): InferenceResult {
        val decision = route(prompt)
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Inference route: ${decision.tier} — ${decision.reason}")

        return when (decision.tier) {
            SwarmRouteDecision.InferenceTier.LOCAL_TEMPLATE -> {
                val response = generateLocalResponse(prompt)
                InferenceResult(
                    success = true,
                    text = response,
                    tier = decision.tier,
                    latencyMs = System.currentTimeMillis() - startTime,
                    model = "local_template"
                )
            }

            SwarmRouteDecision.InferenceTier.ON_DEVICE_LLM -> {
                try {
                    val result = onDeviceLLMManager!!.generate(prompt)
                    InferenceResult(
                        success = result.success,
                        text = result.text,
                        tier = decision.tier,
                        latencyMs = System.currentTimeMillis() - startTime,
                        model = result.modelId ?: "mnn_on_device"
                    )
                } catch (e: Exception) {
                    // Fallback to edge or cloud
                    Log.w(TAG, "On-device LLM failed, falling back: ${e.message}")
                    executeEdgeFallback(prompt, apiKey, apiClient, startTime)
                }
            }

            SwarmRouteDecision.InferenceTier.PICOCLAW_EDGE -> {
                try {
                    val node = bridge.getNodes()[decision.targetNodeId]
                        ?: throw RuntimeException("Node ${decision.targetNodeId} not found")

                    val msg = SwarmProtocol.inferenceRequest(
                        "tronprotocol_android",
                        node.nodeId,
                        prompt
                    )
                    val response = bridge.dispatchToNode(node, "/agent", msg.toJson().toString())
                    node.recordSuccess()

                    val text = parseInferenceResponse(response)
                    InferenceResult(
                        success = true,
                        text = text,
                        tier = decision.tier,
                        latencyMs = System.currentTimeMillis() - startTime,
                        model = decision.suggestedModel ?: "picoclaw_edge",
                        nodeId = decision.targetNodeId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Edge inference failed, falling back to cloud: ${e.message}")
                    executeCloudFallback(prompt, apiKey, apiClient, startTime)
                }
            }

            SwarmRouteDecision.InferenceTier.CLOUD_SONNET -> {
                executeCloud(prompt, apiKey, apiClient, AnthropicApiClient.MODEL_SONNET, startTime)
            }

            SwarmRouteDecision.InferenceTier.CLOUD_OPUS -> {
                executeCloud(prompt, apiKey, apiClient, AnthropicApiClient.MODEL_OPUS, startTime)
            }
        }
    }

    data class InferenceResult(
        val success: Boolean,
        val text: String?,
        val tier: SwarmRouteDecision.InferenceTier,
        val latencyMs: Long,
        val model: String,
        val nodeId: String? = null,
        val error: String? = null
    )

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun isTrivialPrompt(lowered: String): Boolean {
        val shortPrompt = lowered.length < 180
        val simpleIntent = lowered.contains("hello") || lowered.contains("summary") ||
                lowered.contains("todo") || lowered.contains("status") || lowered.contains("help")
        val noCodeMutation = !lowered.contains("modify code") && !lowered.contains("patch") &&
                !lowered.contains("rewrite")
        return shortPrompt && simpleIntent && noCodeMutation
    }

    private fun isOnDeviceSuitable(lowered: String): Boolean {
        val mediumPrompt = lowered.length < 500
        val hasOnDeviceIntent = EDGE_SUITABLE_INTENTS.any { lowered.contains(it) }
        return mediumPrompt && hasOnDeviceIntent
    }

    private fun selectEdgeNode(lowered: String): SwarmNode? {
        // Check if prompt is suitable for edge processing
        val isEdgeSuitable = lowered.length < 2000 &&
                EDGE_SUITABLE_INTENTS.any { lowered.contains(it) }

        if (!isEdgeSuitable) return null

        return bridge.getBestNodeForCapability(NodeCapability.INFERENCE)
    }

    private fun generateLocalResponse(prompt: String): String {
        val lowered = prompt.lowercase(Locale.US)
        return when {
            lowered.contains("hello") || lowered.contains("hi") ->
                "Hello! TronProtocol swarm is operational with ${bridge.getOnlineNodes().size} edge nodes online."
            lowered.contains("status") ->
                "Swarm status: ${bridge.getOnlineNodes().size}/${bridge.getNodes().size} nodes online."
            lowered.contains("help") ->
                "Available swarm capabilities: inference, gateway messaging, voice transcription, skill execution, memory sync."
            else -> "Acknowledged."
        }
    }

    private fun parseInferenceResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            json.optString("response",
                json.optString("text",
                    json.optJSONObject("payload")?.optString("text", response) ?: response
                )
            )
        } catch (_: Exception) {
            response
        }
    }

    private fun executeEdgeFallback(
        prompt: String, apiKey: String?, apiClient: AnthropicApiClient?, startTime: Long
    ): InferenceResult {
        val edgeNode = bridge.getBestNodeForCapability(NodeCapability.INFERENCE)
        if (edgeNode != null) {
            try {
                val msg = SwarmProtocol.inferenceRequest("tronprotocol_android", edgeNode.nodeId, prompt)
                val response = bridge.dispatchToNode(edgeNode, "/agent", msg.toJson().toString())
                edgeNode.recordSuccess()
                return InferenceResult(
                    success = true,
                    text = parseInferenceResponse(response),
                    tier = SwarmRouteDecision.InferenceTier.PICOCLAW_EDGE,
                    latencyMs = System.currentTimeMillis() - startTime,
                    model = "picoclaw_edge",
                    nodeId = edgeNode.nodeId
                )
            } catch (e: Exception) {
                edgeNode.recordFailure()
            }
        }
        return executeCloudFallback(prompt, apiKey, apiClient, startTime)
    }

    private fun executeCloudFallback(
        prompt: String, apiKey: String?, apiClient: AnthropicApiClient?, startTime: Long
    ): InferenceResult {
        return executeCloud(prompt, apiKey, apiClient, AnthropicApiClient.MODEL_SONNET, startTime)
    }

    private fun executeCloud(
        prompt: String, apiKey: String?, apiClient: AnthropicApiClient?,
        model: String, startTime: Long
    ): InferenceResult {
        if (apiKey == null || apiClient == null) {
            return InferenceResult(
                success = false,
                text = null,
                tier = SwarmRouteDecision.InferenceTier.CLOUD_SONNET,
                latencyMs = System.currentTimeMillis() - startTime,
                model = model,
                error = "API key or client not configured"
            )
        }

        return try {
            val response = apiClient.createGuidance(apiKey, model, prompt, 600)
            InferenceResult(
                success = true,
                text = response,
                tier = if (model == AnthropicApiClient.MODEL_OPUS)
                    SwarmRouteDecision.InferenceTier.CLOUD_OPUS
                else SwarmRouteDecision.InferenceTier.CLOUD_SONNET,
                latencyMs = System.currentTimeMillis() - startTime,
                model = model
            )
        } catch (e: Exception) {
            InferenceResult(
                success = false,
                text = null,
                tier = SwarmRouteDecision.InferenceTier.CLOUD_SONNET,
                latencyMs = System.currentTimeMillis() - startTime,
                model = model,
                error = e.message
            )
        }
    }
}
