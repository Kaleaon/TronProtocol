package com.tronprotocol.app.guidance

import java.util.Locale

/**
 * Local decision router that picks local handling vs cloud model tier.
 */
class DecisionRouter {

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

    class RouteDecision private constructor(
        val useLocal: Boolean,
        val cloudModel: String?,
        val reason: String
    ) {
        companion object {
            fun local(reason: String): RouteDecision {
                return RouteDecision(true, null, reason)
            }

            fun cloud(model: String, reason: String): RouteDecision {
                return RouteDecision(false, model, reason)
            }
        }
    }

    companion object {
        private val HIGH_STAKES_TERMS = setOf(
            "identity", "persona", "self-mod", "self mod", "self modification",
            "ethical kernel", "ethics", "policy override", "approval", "governance"
        )
    }
}
