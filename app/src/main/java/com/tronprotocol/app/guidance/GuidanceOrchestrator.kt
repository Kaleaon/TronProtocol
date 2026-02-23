package com.tronprotocol.app.guidance

import android.text.TextUtils
import android.util.Log
import com.tronprotocol.app.llm.HereticModelManager
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Orchestrates local-vs-ondevice-vs-cloud guidance, caching, and ethical-kernel validation.
 *
 * Routing tiers:
 * 1. Local — template responses for trivial prompts
 * 2. On-Device LLM — MNN-powered inference for medium-complexity queries (offline capable)
 * 3. Cloud (Sonnet/Opus) — Anthropic API for complex or high-stakes guidance
 *
 * The on-device LLM tier is used when:
 * - DecisionRouter routes the prompt to on-device (medium complexity, no high-stakes terms)
 * - OR as a fallback when all cloud models are exhausted (offline resilience)
 *
 * @see <a href="https://github.com/alibaba/MNN">Alibaba MNN</a>
 */
class GuidanceOrchestrator(
    private val anthropicApiClient: AnthropicApiClient,
    private val decisionRouter: DecisionRouter,
    private val ethicalKernelValidator: EthicalKernelValidator,
    private val ragStore: RAGStore?,
    private val onDeviceLLMManager: OnDeviceLLMManager? = null,
    private val hereticModelManager: HereticModelManager? = null
) {

    @Throws(Exception::class)
    fun guide(apiKey: String, prompt: String): GuidanceResponse {
        val promptCheck = ethicalKernelValidator.validatePrompt(prompt)
        if (!promptCheck.allowed) {
            return GuidanceResponse.error(promptCheck.message, "blocked")
        }

        val decision = decisionRouter.decide(prompt)
        if (decision.useLocal) {
            val localAnswer = buildLocalResponse(prompt, decision.reason)
            val localCheck = ethicalKernelValidator.validateResponse(localAnswer)
            if (!localCheck.allowed) {
                return GuidanceResponse.error(localCheck.message, "local_blocked")
            }
            cache(prompt, localAnswer, "local")
            return GuidanceResponse.success(localAnswer, "local", "local-brain", true)
        }

        // Heretic model tier: uncensored model with constitutional values enforcement.
        // The model has no baked-in alignment — the ConstitutionalValuesEngine provides
        // transparent, auditable safety controls instead.
        if (decision.useHereticModel) {
            val hereticResult = tryHereticModel(prompt)
            if (hereticResult != null) {
                // Heretic models apply their own constitutional values validation
                // internally via HereticModelManager, so we skip the ethicalKernelValidator
                // response check to avoid double-gating.
                cache(prompt, hereticResult, "heretic_model")
                val modelId = hereticModelManager?.activeConfig?.modelId ?: "heretic_local"
                return GuidanceResponse.success(hereticResult, "heretic_model", modelId, false)
            }
            // Fall through to on-device LLM if heretic model failed
            Log.d(TAG, "Heretic model unavailable, falling through to on-device LLM")
        }

        // On-device LLM tier: handle medium-complexity prompts locally via MNN
        if (decision.useOnDeviceLLM) {
            val onDeviceResult = tryOnDeviceLLM(prompt)
            if (onDeviceResult != null) {
                val llmCheck = ethicalKernelValidator.validateResponse(onDeviceResult)
                if (!llmCheck.allowed) {
                    return GuidanceResponse.error(llmCheck.message, "on_device_llm_blocked")
                }
                cache(prompt, onDeviceResult, "on_device_llm")
                val modelId = onDeviceLLMManager?.activeConfig?.modelId ?: "mnn_local"
                return GuidanceResponse.success(onDeviceResult, "on_device_llm", modelId, false)
            }
            // Fall through to cloud if on-device LLM failed
            Log.d(TAG, "On-device LLM unavailable, falling through to cloud")
        }

        val cached = lookupCache(prompt)
        if (!TextUtils.isEmpty(cached)) {
            val cacheCheck = ethicalKernelValidator.validateResponse(cached)
            if (!cacheCheck.allowed) {
                return GuidanceResponse.error(cacheCheck.message, "cache_blocked")
            }
            return GuidanceResponse.success(cached!!, "cache", decision.cloudModel, true)
        }

        // Cloud tier: call Anthropic API
        try {
            val cloudAnswer = anthropicApiClient.createGuidance(
                apiKey, decision.cloudModel!!, prompt, 600
            )
            val cloudCheck = ethicalKernelValidator.validateResponse(cloudAnswer)
            if (!cloudCheck.allowed) {
                return GuidanceResponse.error(cloudCheck.message, "cloud_blocked")
            }

            cache(prompt, cloudAnswer, decision.cloudModel)
            return GuidanceResponse.success(cloudAnswer, "cloud", decision.cloudModel, false)
        } catch (e: AnthropicApiClient.AnthropicException) {
            // If cloud fails, try heretic model first (constitution-gated), then standard on-device LLM
            if (hereticModelManager != null && hereticModelManager.isReady) {
                Log.d(TAG, "Cloud API failed (${e.message}), falling back to heretic model")
                val hereticFallback = tryHereticModel(prompt)
                if (hereticFallback != null) {
                    val modelId = hereticModelManager.activeConfig?.modelId ?: "heretic_fallback"
                    return GuidanceResponse.success(
                        hereticFallback, "heretic_model_fallback", modelId, false
                    )
                }
            }

            // If cloud fails and on-device LLM is available, use it as fallback
            if (onDeviceLLMManager != null && onDeviceLLMManager.isReady) {
                Log.d(TAG, "Cloud API failed (${e.message}), falling back to on-device LLM")
                val fallbackResult = tryOnDeviceLLM(prompt)
                if (fallbackResult != null) {
                    val fallbackCheck = ethicalKernelValidator.validateResponse(fallbackResult)
                    if (!fallbackCheck.allowed) {
                        return GuidanceResponse.error(fallbackCheck.message, "fallback_blocked")
                    }
                    val modelId = onDeviceLLMManager.activeConfig?.modelId ?: "mnn_fallback"
                    return GuidanceResponse.success(
                        fallbackResult, "on_device_llm_fallback", modelId, false
                    )
                }
            }
            throw e
        }
    }

    /**
     * Attempt to generate a response using a heretic (uncensored) model.
     * The [HereticModelManager] applies constitutional values evaluation on both
     * prompt and response, providing transparent safety without opaque model alignment.
     *
     * Returns null if the heretic model is not available or generation fails.
     */
    private fun tryHereticModel(prompt: String): String? {
        val manager = hereticModelManager ?: return null
        if (!manager.isReady) return null

        return try {
            val result = manager.generate(prompt)
            if (result.success && result.text != null) {
                Log.d(TAG, "Heretic model generated ${result.tokensGenerated} tokens " +
                        "in ${result.latencyMs}ms (${String.format("%.1f", result.tokensPerSecond)} tok/s) " +
                        "[constitution v${result.constitutionVersion}]")
                result.text
            } else {
                Log.w(TAG, "Heretic model generation failed: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heretic model exception: ${e.message}")
            null
        }
    }

    /**
     * Attempt to generate a response using the on-device MNN LLM.
     * Returns null if the LLM is not available or generation fails.
     */
    private fun tryOnDeviceLLM(prompt: String): String? {
        val manager = onDeviceLLMManager ?: return null
        if (!manager.isReady) return null

        return try {
            val result = manager.generate(prompt)
            if (result.success && result.text != null) {
                Log.d(TAG, "On-device LLM generated ${result.tokensGenerated} tokens " +
                        "in ${result.latencyMs}ms (${String.format("%.1f", result.tokensPerSecond)} tok/s)")
                result.text
            } else {
                Log.w(TAG, "On-device LLM generation failed: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "On-device LLM exception: ${e.message}")
            null
        }
    }

    private fun buildLocalResponse(prompt: String, reason: String): String {
        return "[Local Guidance]\n" +
                "Decision: $reason\n" +
                "Prompt: $prompt\n" +
                "Guidance: This request is within local capability. For high-stakes identity, self-mod approval, " +
                "or ethical-kernel work, routing will escalate to ${AnthropicApiClient.MODEL_OPUS}."
    }

    private fun cache(prompt: String, answer: String, sourceModel: String) {
        if (ragStore == null) {
            return
        }

        try {
            val hash = hash(prompt)
            val payload = "$CACHE_PREFIX|$hash|$sourceModel|Q:$prompt|A:$answer"
            ragStore.addKnowledge(payload, "guidance_cache")
        } catch (ignored: Exception) {
            // Best-effort cache.
        }
    }

    private fun lookupCache(prompt: String): String? {
        if (ragStore == null) {
            return null
        }

        try {
            val hash = hash(prompt)
            val results = ragStore.retrieve(hash, RetrievalStrategy.KEYWORD, 5)
            for (result in results) {
                val content = result.chunk.content
                val marker = "$CACHE_PREFIX|$hash|"
                if (content != null && content.startsWith(marker)) {
                    val idx = content.indexOf("|A:")
                    if (idx >= 0 && idx + 3 <= content.length) {
                        return content.substring(idx + 3).trim()
                    }
                }
            }
        } catch (ignored: Exception) {
            // No cache fallback.
        }
        return null
    }

    @Throws(Exception::class)
    private fun hash(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(StandardCharsets.UTF_8))
        val out = StringBuilder()
        for (i in 0 until minOf(8, digest.size)) {
            out.append(String.format("%02x", digest[i]))
        }
        return out.toString()
    }

    class GuidanceResponse private constructor(
        val success: Boolean,
        val answer: String?,
        val route: String,
        val model: String?,
        val cacheHit: Boolean,
        val error: String?
    ) {
        companion object {
            fun success(answer: String, route: String, model: String?, cacheHit: Boolean): GuidanceResponse {
                return GuidanceResponse(true, answer, route, model, cacheHit, null)
            }

            fun error(error: String, route: String): GuidanceResponse {
                return GuidanceResponse(false, null, route, null, false, error)
            }
        }
    }

    companion object {
        private const val TAG = "GuidanceOrchestrator"
        private const val CACHE_PREFIX = "GUIDANCE_CACHE"
    }
}
