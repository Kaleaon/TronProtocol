package com.tronprotocol.app.inference

import android.util.Log

/**
 * Template-based prompt construction engine that adapts prompts based on
 * query type, inference tier, and available context.
 *
 * Each template defines:
 * - A system prefix tuned for the task type
 * - Context injection rules (how much RAG context to include)
 * - Response format hints
 * - Token budget recommendations
 */
class PromptTemplateEngine {

    /**
     * Detected query category used to select the right template.
     */
    enum class QueryCategory {
        GENERAL,
        FACTUAL,
        CREATIVE,
        ANALYSIS,
        CODE,
        DEVICE_CONTROL,
        SUMMARIZATION,
        CONVERSATION
    }

    data class PromptTemplate(
        val category: QueryCategory,
        val systemPrefix: String,
        val contextStrategy: ContextStrategy,
        val responseHint: String,
        val recommendedMaxTokens: Int,
        val includeDeviceState: Boolean = false
    )

    enum class ContextStrategy {
        /** No RAG context needed. */
        NONE,
        /** Include top-k relevant memories. */
        RAG_TOP_K,
        /** Include recent conversation + RAG. */
        CONVERSATION_PLUS_RAG,
        /** Include full conversation history. */
        FULL_CONVERSATION
    }

    data class ConstructedPrompt(
        val fullPrompt: String,
        val category: QueryCategory,
        val template: PromptTemplate,
        val estimatedTokens: Int,
        val contextIncluded: Boolean
    )

    /**
     * Classify a user query into a category using keyword heuristics.
     */
    fun classifyQuery(query: String): QueryCategory {
        val lower = query.lowercase().trim()

        return when {
            CODE_PATTERNS.any { lower.contains(it) } -> QueryCategory.CODE
            DEVICE_CONTROL_PATTERNS.any { lower.contains(it) } -> QueryCategory.DEVICE_CONTROL
            SUMMARIZE_PATTERNS.any { lower.contains(it) } -> QueryCategory.SUMMARIZATION
            ANALYSIS_PATTERNS.any { lower.contains(it) } -> QueryCategory.ANALYSIS
            CREATIVE_PATTERNS.any { lower.contains(it) } -> QueryCategory.CREATIVE
            FACTUAL_PATTERNS.any { lower.contains(it) } -> QueryCategory.FACTUAL
            GREETING_PATTERNS.any { lower.matches(Regex(it)) } -> QueryCategory.CONVERSATION
            else -> QueryCategory.GENERAL
        }
    }

    /**
     * Get the template for a given query category.
     */
    fun getTemplate(category: QueryCategory): PromptTemplate {
        return templates[category] ?: templates[QueryCategory.GENERAL]!!
    }

    /**
     * Construct a full prompt from a user query, optionally including context.
     */
    fun constructPrompt(
        userQuery: String,
        ragContext: String? = null,
        conversationContext: String? = null,
        deviceState: String? = null,
        tier: InferenceTier = InferenceTier.LOCAL_ON_DEMAND
    ): ConstructedPrompt {
        val category = classifyQuery(userQuery)
        val template = getTemplate(category)

        val prompt = buildString {
            // System prefix
            append(template.systemPrefix)
            append("\n\n")

            // Tier-specific adaptations
            when (tier) {
                InferenceTier.LOCAL_ALWAYS_ON,
                InferenceTier.LOCAL_ON_DEMAND -> {
                    append("[Instruction: Keep response concise. You are running on-device.]\n\n")
                }
                InferenceTier.CLOUD_FALLBACK -> {
                    // Cloud can handle longer context
                }
            }

            // Context injection based on strategy
            when (template.contextStrategy) {
                ContextStrategy.RAG_TOP_K -> {
                    ragContext?.let {
                        if (it.isNotBlank()) {
                            append("[Relevant Memory]\n$it\n\n")
                        }
                    }
                }
                ContextStrategy.CONVERSATION_PLUS_RAG -> {
                    conversationContext?.let {
                        if (it.isNotBlank()) {
                            append("[Conversation Context]\n$it\n\n")
                        }
                    }
                    ragContext?.let {
                        if (it.isNotBlank()) {
                            append("[Relevant Memory]\n$it\n\n")
                        }
                    }
                }
                ContextStrategy.FULL_CONVERSATION -> {
                    conversationContext?.let {
                        if (it.isNotBlank()) {
                            append("[Conversation History]\n$it\n\n")
                        }
                    }
                }
                ContextStrategy.NONE -> { }
            }

            // Device state for device-control queries
            if (template.includeDeviceState && deviceState != null) {
                append("[Device State]\n$deviceState\n\n")
            }

            // Response format hint
            if (template.responseHint.isNotBlank()) {
                append("[Format: ${template.responseHint}]\n\n")
            }

            // User query
            append("[User]\n$userQuery")
        }

        val estimatedTokens = AIContextManager.estimateTokens(prompt)
        Log.d(TAG, "Constructed ${category.name} prompt: ~$estimatedTokens tokens, " +
                "context=${template.contextStrategy.name}")

        return ConstructedPrompt(
            fullPrompt = prompt,
            category = category,
            template = template,
            estimatedTokens = estimatedTokens,
            contextIncluded = ragContext != null || conversationContext != null
        )
    }

    /**
     * Get the recommended max tokens for a query category and tier.
     */
    fun getRecommendedMaxTokens(category: QueryCategory, tier: InferenceTier): Int {
        val base = getTemplate(category).recommendedMaxTokens
        return when (tier) {
            InferenceTier.LOCAL_ALWAYS_ON -> (base * 0.5f).toInt().coerceAtLeast(64)
            InferenceTier.LOCAL_ON_DEMAND -> base
            InferenceTier.CLOUD_FALLBACK -> (base * 2.0f).toInt().coerceAtMost(4096)
        }
    }

    companion object {
        private const val TAG = "PromptTemplateEngine"

        private val templates = mapOf(
            QueryCategory.GENERAL to PromptTemplate(
                category = QueryCategory.GENERAL,
                systemPrefix = "You are Tron AI, a helpful assistant on the user's Android device.",
                contextStrategy = ContextStrategy.CONVERSATION_PLUS_RAG,
                responseHint = "Clear, helpful response",
                recommendedMaxTokens = 512
            ),
            QueryCategory.FACTUAL to PromptTemplate(
                category = QueryCategory.FACTUAL,
                systemPrefix = "You are a knowledgeable assistant. Provide accurate, factual answers.",
                contextStrategy = ContextStrategy.RAG_TOP_K,
                responseHint = "Direct factual answer with source if available",
                recommendedMaxTokens = 256
            ),
            QueryCategory.CREATIVE to PromptTemplate(
                category = QueryCategory.CREATIVE,
                systemPrefix = "You are a creative assistant. Help with writing, brainstorming, and creative tasks.",
                contextStrategy = ContextStrategy.CONVERSATION_PLUS_RAG,
                responseHint = "Creative, engaging output",
                recommendedMaxTokens = 1024
            ),
            QueryCategory.ANALYSIS to PromptTemplate(
                category = QueryCategory.ANALYSIS,
                systemPrefix = "You are an analytical assistant. Provide thorough analysis with structured reasoning.",
                contextStrategy = ContextStrategy.CONVERSATION_PLUS_RAG,
                responseHint = "Structured analysis with key points",
                recommendedMaxTokens = 768
            ),
            QueryCategory.CODE to PromptTemplate(
                category = QueryCategory.CODE,
                systemPrefix = "You are a programming assistant. Provide clean, working code with brief explanations.",
                contextStrategy = ContextStrategy.RAG_TOP_K,
                responseHint = "Code with minimal explanation",
                recommendedMaxTokens = 768
            ),
            QueryCategory.DEVICE_CONTROL to PromptTemplate(
                category = QueryCategory.DEVICE_CONTROL,
                systemPrefix = "You are a device control assistant. Execute device actions precisely and confirm results.",
                contextStrategy = ContextStrategy.NONE,
                responseHint = "Action confirmation",
                recommendedMaxTokens = 128,
                includeDeviceState = true
            ),
            QueryCategory.SUMMARIZATION to PromptTemplate(
                category = QueryCategory.SUMMARIZATION,
                systemPrefix = "You are a summarization assistant. Provide concise, accurate summaries.",
                contextStrategy = ContextStrategy.FULL_CONVERSATION,
                responseHint = "Concise summary with key points",
                recommendedMaxTokens = 512
            ),
            QueryCategory.CONVERSATION to PromptTemplate(
                category = QueryCategory.CONVERSATION,
                systemPrefix = "You are Tron AI, a friendly conversational assistant on the user's device.",
                contextStrategy = ContextStrategy.CONVERSATION_PLUS_RAG,
                responseHint = "Warm, natural conversational response",
                recommendedMaxTokens = 256
            )
        )

        private val CODE_PATTERNS = listOf(
            "code", "function", "class", "variable", "debug", "error",
            "compile", "syntax", "programming", "algorithm", "api",
            "regex", "json", "xml", "html", "css", "sql", "python",
            "kotlin", "java", "javascript", "implement", "refactor"
        )

        private val DEVICE_CONTROL_PATTERNS = listOf(
            "send sms", "call ", "set alarm", "set timer", "turn on",
            "turn off", "open app", "take photo", "screenshot", "volume",
            "brightness", "wifi", "bluetooth", "flashlight"
        )

        private val SUMMARIZE_PATTERNS = listOf(
            "summarize", "summary", "tldr", "recap", "overview",
            "key points", "main ideas", "condense"
        )

        private val ANALYSIS_PATTERNS = listOf(
            "analyze", "compare", "evaluate", "pros and cons",
            "trade-off", "strengths and weaknesses", "assessment",
            "critique", "review", "benchmark"
        )

        private val CREATIVE_PATTERNS = listOf(
            "write a story", "poem", "creative", "imagine", "brainstorm",
            "fiction", "screenplay", "lyrics", "compose", "invent"
        )

        private val FACTUAL_PATTERNS = listOf(
            "what is", "who is", "when did", "where is", "how many",
            "define ", "meaning of", "fact", "capital of", "population"
        )

        private val GREETING_PATTERNS = listOf(
            "^(hi|hello|hey|good morning|good evening|good afternoon|howdy|yo|sup)\\b.*",
            "^(what'?s up|how are you|how'?s it going).*"
        )
    }
}
