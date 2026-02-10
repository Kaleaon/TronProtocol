package com.tronprotocol.app.guidance

import android.text.TextUtils
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Orchestrates local-vs-cloud guidance, caching, and ethical-kernel validation.
 */
class GuidanceOrchestrator(
    private val anthropicApiClient: AnthropicApiClient,
    private val decisionRouter: DecisionRouter,
    private val ethicalKernelValidator: EthicalKernelValidator,
    private val ragStore: RAGStore?
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

        val cached = lookupCache(prompt)
        if (!TextUtils.isEmpty(cached)) {
            val cacheCheck = ethicalKernelValidator.validateResponse(cached)
            if (!cacheCheck.allowed) {
                return GuidanceResponse.error(cacheCheck.message, "cache_blocked")
            }
            return GuidanceResponse.success(cached!!, "cache", decision.cloudModel, true)
        }

        val cloudAnswer = anthropicApiClient.createGuidance(apiKey, decision.cloudModel!!, prompt, 600)
        val cloudCheck = ethicalKernelValidator.validateResponse(cloudAnswer)
        if (!cloudCheck.allowed) {
            return GuidanceResponse.error(cloudCheck.message, "cloud_blocked")
        }

        cache(prompt, cloudAnswer, decision.cloudModel)
        return GuidanceResponse.success(cloudAnswer, "cloud", decision.cloudModel, false)
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
        private const val CACHE_PREFIX = "GUIDANCE_CACHE"
    }
}
