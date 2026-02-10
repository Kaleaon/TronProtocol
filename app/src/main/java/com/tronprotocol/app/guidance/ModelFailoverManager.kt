package com.tronprotocol.app.guidance

import android.util.Log
import com.tronprotocol.app.llm.OnDeviceLLMManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Model Failover Manager — classified error handling with model rotation
 * and exponential backoff.
 *
 * Inspired by OpenClaw's model failover pipeline:
 * - Classifies errors by type (auth, billing, rate_limit, context_overflow, timeout, overloaded)
 * - Rotates between models based on error type
 * - Applies exponential backoff per model
 * - Tracks model health and availability
 * - Auto-compaction trigger on context overflow
 *
 * Wraps the existing AnthropicApiClient to add resilient multi-model support.
 */
class ModelFailoverManager(
    private val apiClient: AnthropicApiClient,
    models: List<ModelProfile> = defaultModels()
) {

    /** Profile for a model with its capabilities and limits. */
    data class ModelProfile(
        val modelId: String,
        val displayName: String,
        val maxTokens: Int,
        val contextWindow: Int,
        val costTier: CostTier,
        val capabilities: Set<String> = emptySet()
    )

    enum class CostTier { LOW, MEDIUM, HIGH }

    /** Classification of API errors for routing decisions. */
    enum class FailoverReason {
        AUTH_ERROR,           // 401 — bad key, rotate profile
        BILLING_ERROR,        // 402 — payment issue, long cooldown
        RATE_LIMIT,           // 429 — throttled, backoff + rotate
        CONTEXT_OVERFLOW,     // context too large, trigger compaction
        TIMEOUT,              // request timed out, retry next model
        OVERLOADED,           // 529 — server overloaded, exponential backoff
        CONTENT_FILTERED,     // safety filter triggered
        UNKNOWN               // unclassified error
    }

    /** Result of a failover-managed request. */
    data class FailoverResult(
        val success: Boolean,
        val response: String?,
        val modelUsed: String?,
        val attemptsCount: Int,
        val totalLatencyMs: Long,
        val failoverReasons: List<FailoverReason>,
        val error: String?
    )

    // Model registry and health tracking
    private val modelProfiles = models.associateBy { it.modelId }
    private val modelOrder = models.map { it.modelId }.toMutableList()
    private val modelCooldowns = ConcurrentHashMap<String, Long>()
    private val modelFailureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val modelSuccessCounts = ConcurrentHashMap<String, AtomicInteger>()

    // Compaction callback for context overflow
    private var compactionCallback: (() -> String?)? = null

    // On-device LLM for ultimate fallback when all cloud models are exhausted
    private var onDeviceLLMManager: OnDeviceLLMManager? = null

    fun setCompactionCallback(callback: () -> String?) {
        this.compactionCallback = callback
    }

    /**
     * Set the on-device LLM manager for offline fallback.
     * When all cloud models are exhausted, the on-device MNN LLM
     * will be used as a last resort if available and ready.
     */
    fun setOnDeviceLLMFallback(manager: OnDeviceLLMManager?) {
        this.onDeviceLLMManager = manager
    }

    /**
     * Execute a guidance request with automatic failover across models.
     */
    fun executeWithFailover(
        apiKey: String,
        prompt: String,
        maxTokens: Int = 600,
        preferredModel: String? = null
    ): FailoverResult {
        val startTime = System.currentTimeMillis()
        val failoverReasons = mutableListOf<FailoverReason>()
        var attempts = 0
        var lastError: String? = null

        // Build ordered list of models to try
        val modelsToTry = buildModelOrder(preferredModel)

        for (modelId in modelsToTry) {
            // Check cooldown
            val cooldownUntil = modelCooldowns[modelId] ?: 0L
            if (System.currentTimeMillis() < cooldownUntil) {
                Log.d(TAG, "Model $modelId still in cooldown until $cooldownUntil")
                continue
            }

            attempts++
            try {
                val response = apiClient.createGuidance(apiKey, modelId, prompt, maxTokens)

                // Success — record and return
                modelSuccessCounts.getOrPut(modelId) { AtomicInteger(0) }.incrementAndGet()
                modelFailureCounts[modelId]?.set(0) // Reset failure count on success

                val latency = System.currentTimeMillis() - startTime
                Log.d(TAG, "Request succeeded on model=$modelId attempt=$attempts latency=${latency}ms")

                return FailoverResult(
                    success = true,
                    response = response,
                    modelUsed = modelId,
                    attemptsCount = attempts,
                    totalLatencyMs = latency,
                    failoverReasons = failoverReasons,
                    error = null
                )
            } catch (e: AnthropicApiClient.AnthropicException) {
                val reason = classifyError(e)
                failoverReasons.add(reason)
                lastError = e.message

                Log.w(TAG, "Model $modelId failed: reason=$reason status=${e.statusCode} msg=${e.message}")

                // Apply cooldown based on failure type
                applyCooldown(modelId, reason)
                modelFailureCounts.getOrPut(modelId) { AtomicInteger(0) }.incrementAndGet()

                // Special handling for context overflow: try compaction
                if (reason == FailoverReason.CONTEXT_OVERFLOW) {
                    val compacted = compactionCallback?.invoke()
                    if (compacted != null) {
                        Log.d(TAG, "Context overflow — retrying with compacted prompt")
                        try {
                            val response = apiClient.createGuidance(apiKey, modelId, compacted, maxTokens)
                            val latency = System.currentTimeMillis() - startTime
                            return FailoverResult(
                                success = true, response = response, modelUsed = modelId,
                                attemptsCount = attempts + 1, totalLatencyMs = latency,
                                failoverReasons = failoverReasons, error = null
                            )
                        } catch (compactError: AnthropicApiClient.AnthropicException) {
                            Log.w(TAG, "Compacted retry also failed: ${compactError.message}")
                        }
                    }
                }

                // Non-retryable errors: stop immediately
                if (!e.isRetryable && reason != FailoverReason.RATE_LIMIT) {
                    break
                }

                // Apply backoff before trying next model
                applyBackoff(attempts)
            }
        }

        // Last resort: try on-device MNN LLM if all cloud models are exhausted
        val llm = onDeviceLLMManager
        if (llm != null && llm.isReady) {
            Log.d(TAG, "All cloud models exhausted — attempting on-device MNN LLM fallback")
            try {
                val result = llm.generate(prompt)
                if (result.success && result.text != null) {
                    val latency = System.currentTimeMillis() - startTime
                    Log.d(TAG, "On-device LLM fallback succeeded: ${result.tokensGenerated} tokens " +
                            "in ${result.latencyMs}ms")
                    return FailoverResult(
                        success = true,
                        response = result.text,
                        modelUsed = result.modelId ?: "mnn_on_device",
                        attemptsCount = attempts + 1,
                        totalLatencyMs = latency,
                        failoverReasons = failoverReasons,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device LLM fallback also failed: ${e.message}")
            }
        }

        val latency = System.currentTimeMillis() - startTime
        return FailoverResult(
            success = false, response = null, modelUsed = null,
            attemptsCount = attempts, totalLatencyMs = latency,
            failoverReasons = failoverReasons,
            error = "All models exhausted after $attempts attempts. Last error: $lastError"
        )
    }

    /**
     * Classify an API error into a failover reason (OpenClaw pattern).
     */
    private fun classifyError(e: AnthropicApiClient.AnthropicException): FailoverReason {
        return when (e.statusCode) {
            401 -> FailoverReason.AUTH_ERROR
            402 -> FailoverReason.BILLING_ERROR
            429 -> FailoverReason.RATE_LIMIT
            529 -> FailoverReason.OVERLOADED
            else -> {
                val msg = e.message?.lowercase() ?: ""
                when {
                    msg.contains("context") || msg.contains("token") -> FailoverReason.CONTEXT_OVERFLOW
                    msg.contains("timeout") || msg.contains("timed out") -> FailoverReason.TIMEOUT
                    msg.contains("safety") || msg.contains("filter") -> FailoverReason.CONTENT_FILTERED
                    e.statusCode >= 500 -> FailoverReason.OVERLOADED
                    else -> FailoverReason.UNKNOWN
                }
            }
        }
    }

    /**
     * Apply model-specific cooldown based on error type.
     */
    private fun applyCooldown(modelId: String, reason: FailoverReason) {
        val cooldownMs = when (reason) {
            FailoverReason.AUTH_ERROR -> COOLDOWN_AUTH_MS
            FailoverReason.BILLING_ERROR -> COOLDOWN_BILLING_MS
            FailoverReason.RATE_LIMIT -> {
                val failures = modelFailureCounts[modelId]?.get() ?: 1
                COOLDOWN_RATE_LIMIT_BASE_MS * (1L shl (failures - 1).coerceAtMost(6))
            }
            FailoverReason.OVERLOADED -> {
                val failures = modelFailureCounts[modelId]?.get() ?: 1
                COOLDOWN_OVERLOADED_BASE_MS * (1L shl (failures - 1).coerceAtMost(6))
            }
            FailoverReason.TIMEOUT -> COOLDOWN_TIMEOUT_MS
            FailoverReason.CONTENT_FILTERED -> 0L // Don't cooldown on content filter
            FailoverReason.CONTEXT_OVERFLOW -> 0L
            FailoverReason.UNKNOWN -> COOLDOWN_UNKNOWN_MS
        }

        if (cooldownMs > 0) {
            modelCooldowns[modelId] = System.currentTimeMillis() + cooldownMs
            Log.d(TAG, "Model $modelId cooled down for ${cooldownMs}ms due to $reason")
        }
    }

    private fun applyBackoff(attempt: Int) {
        val backoffMs = BACKOFF_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4))
        val clampedMs = backoffMs.coerceAtMost(BACKOFF_MAX_MS)
        try {
            Thread.sleep(clampedMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun buildModelOrder(preferredModel: String?): List<String> {
        if (preferredModel != null && preferredModel in modelProfiles) {
            return listOf(preferredModel) + modelOrder.filter { it != preferredModel }
        }
        return modelOrder.toList()
    }

    /**
     * Get health information for all models.
     */
    fun getModelHealth(): Map<String, Map<String, Any>> {
        val health = mutableMapOf<String, Map<String, Any>>()
        for (modelId in modelOrder) {
            val cooldownUntil = modelCooldowns[modelId] ?: 0L
            val isAvailable = System.currentTimeMillis() >= cooldownUntil
            health[modelId] = mapOf(
                "available" to isAvailable,
                "successes" to (modelSuccessCounts[modelId]?.get() ?: 0),
                "failures" to (modelFailureCounts[modelId]?.get() ?: 0),
                "cooldown_remaining_ms" to maxOf(0L, cooldownUntil - System.currentTimeMillis()),
                "cost_tier" to (modelProfiles[modelId]?.costTier?.name ?: "UNKNOWN")
            )
        }
        return health
    }

    /**
     * Reset cooldowns (e.g., when a new API key is configured).
     */
    fun resetCooldowns() {
        modelCooldowns.clear()
        modelFailureCounts.clear()
        Log.d(TAG, "All model cooldowns and failure counts reset")
    }

    companion object {
        private const val TAG = "ModelFailoverManager"

        // Cooldown durations by error type
        private const val COOLDOWN_AUTH_MS = 60_000L          // 1 minute
        private const val COOLDOWN_BILLING_MS = 86_400_000L   // 24 hours
        private const val COOLDOWN_RATE_LIMIT_BASE_MS = 2_000L
        private const val COOLDOWN_OVERLOADED_BASE_MS = 5_000L
        private const val COOLDOWN_TIMEOUT_MS = 10_000L
        private const val COOLDOWN_UNKNOWN_MS = 5_000L

        // Exponential backoff between attempts
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_MAX_MS = 16_000L

        fun defaultModels(): List<ModelProfile> = listOf(
            ModelProfile(
                modelId = AnthropicApiClient.MODEL_SONNET,
                displayName = "Claude Sonnet 4.5",
                maxTokens = 8192,
                contextWindow = 200_000,
                costTier = CostTier.MEDIUM,
                capabilities = setOf("general", "coding", "analysis")
            ),
            ModelProfile(
                modelId = AnthropicApiClient.MODEL_OPUS,
                displayName = "Claude Opus 4.6",
                maxTokens = 8192,
                contextWindow = 200_000,
                costTier = CostTier.HIGH,
                capabilities = setOf("general", "coding", "analysis", "reasoning", "identity", "ethics")
            )
        )
    }
}
