package com.tronprotocol.app.guidance

import java.util.Locale

/**
 * Local decision router that picks local handling, on-device LLM, or cloud model tier.
 *
 * Routing hierarchy (lowest latency first):
 * 1. Local — trivial prompts handled with template responses
 * 2. On-Device LLM — medium-complexity prompts handled by MNN-powered local model
 * 3. Cloud Sonnet — general guidance delegated to Anthropic Sonnet
 * 4. Cloud Opus — high-stakes decisions requiring maximum reasoning capability
 *
 * The on-device LLM tier uses Alibaba's MNN framework to run quantized models
 * (Qwen, Gemma, DeepSeek up to ~4B parameters) directly on the device's ARM CPU/GPU.
 * This enables offline inference, reduces API costs, and provides low-latency
 * responses for conversational and general-knowledge queries.
 *
 * @see <a href="https://github.com/alibaba/MNN">MNN GitHub</a>
 */
class DecisionRouter {

    /** Whether an on-device LLM is loaded and ready for inference. */
    @Volatile
    var onDeviceLLMAvailable: Boolean = false

    fun decide(prompt: String?): RouteDecision {
        if (prompt == null) {
            return RouteDecision.local("Empty prompt")
        }

        val lowered = prompt.lowercase(Locale.US)

        for (term in HIGH_STAKES_TERMS) {
            if (lowered.contains(term)) {
                return RouteDecision.cloud(
                    AnthropicApiClient.MODEL_OPUS,
                    "High-stakes decision requires Opus tier"
                )
            }
        }

        if (canHandleLocally(lowered)) {
            return RouteDecision.local("Simple prompt handled locally")
        }

        // Route medium-complexity prompts to on-device LLM when available.
        // This avoids cloud API calls for conversational, summary, and
        // general-knowledge queries that a small model (1.5B-4B) can handle.
        if (onDeviceLLMAvailable && canHandleOnDevice(lowered)) {
            return RouteDecision.onDeviceLLM("Prompt routed to on-device MNN LLM")
        }

        return RouteDecision.cloud(
            AnthropicApiClient.MODEL_SONNET,
            "General guidance delegated to Sonnet tier"
        )
    }

    private fun canHandleLocally(lowered: String): Boolean {
        val shortPrompt = lowered.length < 180
        val simpleIntent = lowered.contains("hello") || lowered.contains("summary") ||
                lowered.contains("todo") || lowered.contains("status") || lowered.contains("help")
        val noCodeMutation = !lowered.contains("modify code") && !lowered.contains("patch") &&
                !lowered.contains("rewrite") && !lowered.contains("approve")

        return shortPrompt && simpleIntent && noCodeMutation
    }

    /**
     * Determine if a prompt can be handled by the on-device LLM.
     * Small models (1.5B-4B) handle conversational, factual, and summarization
     * tasks well, but should NOT handle code generation, complex reasoning,
     * or identity/policy decisions.
     */
    private fun canHandleOnDevice(lowered: String): Boolean {
        // Reject prompts needing capabilities beyond a small model
        for (term in CLOUD_REQUIRED_TERMS) {
            if (lowered.contains(term)) return false
        }

        // Accept medium-length prompts for general queries
        val mediumPrompt = lowered.length < 1000

        // Accept prompts that match on-device capable intents
        val onDeviceCapable = ON_DEVICE_INTENTS.any { lowered.contains(it) }
                || lowered.length in 50..500  // Medium-length general queries

        return mediumPrompt && onDeviceCapable
    }

    class RouteDecision private constructor(
        val useLocal: Boolean,
        val useOnDeviceLLM: Boolean,
        val cloudModel: String?,
        val reason: String
    ) {
        companion object {
            fun local(reason: String): RouteDecision {
                return RouteDecision(true, false, null, reason)
            }

            fun onDeviceLLM(reason: String): RouteDecision {
                return RouteDecision(false, true, null, reason)
            }

            fun cloud(model: String, reason: String): RouteDecision {
                return RouteDecision(false, false, model, reason)
            }
        }
    }

    companion object {
        private val HIGH_STAKES_TERMS = setOf(
            "identity", "persona", "self-mod", "self mod", "self modification",
            "ethical kernel", "ethics", "policy override", "approval", "governance"
        )

        /** Terms that require cloud model capabilities beyond a small on-device LLM. */
        private val CLOUD_REQUIRED_TERMS = setOf(
            "modify code", "patch", "rewrite", "approve",
            "refactor", "debug", "compile", "deploy",
            "complex analysis", "legal", "medical advice"
        )

        /** Intent keywords that a small on-device LLM can handle well. */
        private val ON_DEVICE_INTENTS = setOf(
            "explain", "what is", "how does", "describe", "tell me",
            "summarize", "translate", "define", "compare", "list",
            "write", "draft", "suggest", "recommend", "answer",
            "calculate", "convert", "format", "generate"
        )
    }
}
