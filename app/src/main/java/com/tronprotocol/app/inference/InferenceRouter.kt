package com.tronprotocol.app.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.tronprotocol.app.guidance.AnthropicApiClient
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes inference requests to the appropriate tier based on complexity,
 * connectivity, thermal state, and resource availability.
 *
 * Routing strategy from the TronProtocol Pixel 10 spec:
 * - Simple queries (greetings, factual, low complexity) → local SLM
 * - Moderate queries (summarization, analysis) → local SLM with RAG
 * - Complex queries (multi-step reasoning, novel tasks) → cloud (Claude API)
 * - Fallback: if local fails or quality is insufficient, escalate to cloud
 *
 * Connectivity-aware: cloud tier only available when network is reachable.
 * Thermal-aware: under thermal pressure, prefer shorter context and simpler models.
 */
class InferenceRouter(private val context: Context) {

    private val storage = SecureStorage(context)

    /** Whether the local SLM is loaded and ready. */
    private val localModelReady = AtomicBoolean(false)

    /** Whether cloud API is configured and available. */
    private val cloudAvailable = AtomicBoolean(false)

    /** Inference count for diagnostics. */
    private val inferenceCount = AtomicInteger(0)

    /** Per-tier success/failure tracking for adaptive routing. */
    private val tierSuccessCount = mutableMapOf<InferenceTier, Int>()
    private val tierFailureCount = mutableMapOf<InferenceTier, Int>()

    /** Cloud API client for Tier 3 fallback. */
    private var anthropicClient: AnthropicApiClient? = null
    private var apiKey: String? = null

    /**
     * Callback interface for local SLM inference. Implemented by OnDeviceLLMPlugin.
     */
    interface LocalInferenceProvider {
        fun isReady(): Boolean
        fun generate(prompt: String, maxTokens: Int): String
        fun getModelId(): String
    }

    private var localProvider: LocalInferenceProvider? = null

    fun setLocalProvider(provider: LocalInferenceProvider) {
        localProvider = provider
        localModelReady.set(provider.isReady())
    }

    fun configureCloud(apiKey: String) {
        this.apiKey = apiKey
        this.anthropicClient = AnthropicApiClient(
            maxRequestsPerMinute = 30,
            minRequestIntervalMs = 2000L
        )
        cloudAvailable.set(true)
    }

    /**
     * Route and execute an inference request.
     *
     * @param prompt The input prompt
     * @param maxTokens Maximum tokens in response
     * @param complexityHint Estimated complexity (0.0 = trivial, 1.0 = very complex)
     * @param requireLocal Force local-only inference (no cloud fallback)
     */
    fun infer(
        prompt: String,
        maxTokens: Int = 512,
        complexityHint: Float = -1.0f,
        requireLocal: Boolean = false
    ): InferenceResult {
        val startTime = System.currentTimeMillis()
        val complexity = if (complexityHint < 0) estimateComplexity(prompt) else complexityHint
        val tier = selectTier(complexity, requireLocal)

        Log.d(TAG, "Routing inference to ${tier.label} (complexity=${"%.2f".format(complexity)})")

        val result = when (tier) {
            InferenceTier.LOCAL_ALWAYS_ON,
            InferenceTier.LOCAL_ON_DEMAND -> executeLocal(prompt, maxTokens, startTime)
            InferenceTier.CLOUD_FALLBACK -> executeCloud(prompt, maxTokens, startTime)
        }

        // If local failed and cloud is available, try cloud fallback.
        if (result.text.isEmpty() && !requireLocal && tier != InferenceTier.CLOUD_FALLBACK) {
            if (cloudAvailable.get() && isNetworkAvailable()) {
                Log.d(TAG, "Local inference failed, falling back to cloud")
                return executeCloud(prompt, maxTokens, startTime)
            }
        }

        inferenceCount.incrementAndGet()
        trackTierResult(result.tier, result.text.isNotEmpty())
        return result
    }

    /**
     * Estimate prompt complexity based on heuristics.
     */
    private fun estimateComplexity(prompt: String): Float {
        val wordCount = prompt.split(Regex("\\s+")).size
        val hasQuestion = prompt.contains("?")
        val hasMultiStep = prompt.contains(" and ") || prompt.contains(" then ")
        val hasReasoning = REASONING_KEYWORDS.any { prompt.lowercase().contains(it) }

        var score = 0.0f
        if (wordCount > 100) score += 0.2f
        if (wordCount > 300) score += 0.2f
        if (hasQuestion) score += 0.1f
        if (hasMultiStep) score += 0.2f
        if (hasReasoning) score += 0.3f
        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * Select the inference tier based on complexity and constraints.
     */
    private fun selectTier(complexity: Float, requireLocal: Boolean): InferenceTier {
        if (requireLocal) {
            return if (localModelReady.get()) InferenceTier.LOCAL_ON_DEMAND
            else InferenceTier.LOCAL_ALWAYS_ON
        }

        return when {
            complexity < LOCAL_COMPLEXITY_THRESHOLD -> {
                if (localModelReady.get()) InferenceTier.LOCAL_ON_DEMAND
                else InferenceTier.LOCAL_ALWAYS_ON
            }
            complexity < CLOUD_COMPLEXITY_THRESHOLD && localModelReady.get() -> {
                InferenceTier.LOCAL_ON_DEMAND
            }
            cloudAvailable.get() && isNetworkAvailable() -> {
                InferenceTier.CLOUD_FALLBACK
            }
            localModelReady.get() -> {
                // Cloud preferred but unavailable; degrade to local
                InferenceTier.LOCAL_ON_DEMAND
            }
            else -> InferenceTier.LOCAL_ALWAYS_ON
        }
    }

    private fun executeLocal(
        prompt: String,
        maxTokens: Int,
        startTime: Long
    ): InferenceResult {
        val provider = localProvider
        if (provider == null || !provider.isReady()) {
            return InferenceResult.error(
                InferenceTier.LOCAL_ON_DEMAND, "Local model not ready",
                System.currentTimeMillis() - startTime
            )
        }
        return try {
            val text = provider.generate(prompt, maxTokens)
            InferenceResult(
                text = text,
                tier = InferenceTier.LOCAL_ON_DEMAND,
                modelId = provider.getModelId(),
                latencyMs = System.currentTimeMillis() - startTime,
                tokenCount = text.split(Regex("\\s+")).size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local inference failed", e)
            InferenceResult.error(
                InferenceTier.LOCAL_ON_DEMAND,
                e.message ?: "Local inference error",
                System.currentTimeMillis() - startTime
            )
        }
    }

    private fun executeCloud(
        prompt: String,
        maxTokens: Int,
        startTime: Long
    ): InferenceResult {
        val client = anthropicClient
        val key = apiKey
        if (client == null || key == null) {
            return InferenceResult.error(
                InferenceTier.CLOUD_FALLBACK, "Cloud API not configured",
                System.currentTimeMillis() - startTime
            )
        }
        return try {
            val text = client.createGuidance(key, AnthropicApiClient.MODEL_SONNET, prompt, maxTokens)
            InferenceResult(
                text = text,
                tier = InferenceTier.CLOUD_FALLBACK,
                modelId = AnthropicApiClient.MODEL_SONNET,
                latencyMs = System.currentTimeMillis() - startTime,
                tokenCount = text.split(Regex("\\s+")).size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cloud inference failed", e)
            InferenceResult.error(
                InferenceTier.CLOUD_FALLBACK,
                e.message ?: "Cloud inference error",
                System.currentTimeMillis() - startTime
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun trackTierResult(tier: InferenceTier, success: Boolean) {
        if (success) {
            tierSuccessCount[tier] = (tierSuccessCount[tier] ?: 0) + 1
        } else {
            tierFailureCount[tier] = (tierFailureCount[tier] ?: 0) + 1
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "inference_count" to inferenceCount.get(),
        "local_model_ready" to localModelReady.get(),
        "cloud_available" to cloudAvailable.get(),
        "tier_success" to tierSuccessCount.map { (k, v) -> k.label to v }.toMap(),
        "tier_failure" to tierFailureCount.map { (k, v) -> k.label to v }.toMap()
    )

    companion object {
        private const val TAG = "InferenceRouter"
        private const val LOCAL_COMPLEXITY_THRESHOLD = 0.4f
        private const val CLOUD_COMPLEXITY_THRESHOLD = 0.7f
        private val REASONING_KEYWORDS = listOf(
            "explain", "analyze", "compare", "evaluate", "synthesize",
            "why", "how does", "what if", "pros and cons", "trade-off",
            "design", "architect", "plan", "strategy", "implement"
        )
    }
}
