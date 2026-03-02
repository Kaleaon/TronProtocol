package com.tronprotocol.app.persona

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Rich persona definition supporting TavernAI v2 character card format,
 * sampling profiles, and control vector personality steering.
 *
 * Ported from ToolNeuron's Persona entity. Combines legacy fields (name, avatar,
 * system prompt, greeting) with TavernAI v2 spec fields (description, personality,
 * scenario, example messages, alternate greetings, tags) and engine-specific fields
 * (sampling profile, control vectors).
 */
data class Persona(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val avatarUri: String? = null,

    // Core prompting
    val systemPrompt: String = "",
    val greeting: String = "",
    val isDefault: Boolean = false,

    // TavernAI v2 fields
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val exampleMessages: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creatorNotes: String = "",
    val postHistoryInstructions: String = "",

    // Engine fields (JSON strings for backend integration)
    @SerializedName("sampling_profile")
    val samplingProfileJson: String = "{}",
    @SerializedName("control_vectors")
    val controlVectorsJson: String = "{}"
) {

    /**
     * Build the effective system prompt by combining all persona fields.
     * Follows TavernAI v2 prompt assembly order.
     */
    fun buildEffectiveSystemPrompt(userName: String = "User"): String {
        val parts = mutableListOf<String>()

        if (systemPrompt.isNotBlank()) {
            parts.add(applyTemplateVars(systemPrompt, userName))
        }

        if (description.isNotBlank()) {
            parts.add("Character Description: ${applyTemplateVars(description, userName)}")
        }

        if (personality.isNotBlank()) {
            parts.add("Personality: ${applyTemplateVars(personality, userName)}")
        }

        if (scenario.isNotBlank()) {
            parts.add("Scenario: ${applyTemplateVars(scenario, userName)}")
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Build post-history instruction for injection after conversation history.
     */
    fun buildPostHistoryInstruction(userName: String = "User"): String? {
        return if (postHistoryInstructions.isNotBlank()) {
            applyTemplateVars(postHistoryInstructions, userName)
        } else null
    }

    /**
     * Parse the sampling profile JSON into a map.
     */
    fun getSamplingProfile(): Map<String, Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(samplingProfileJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Parse the control vectors JSON into a map of axis → strength.
     */
    fun getControlVectors(): Map<String, Float> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = Gson().fromJson(controlVectorsJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
            raw.mapValues { (it.value as? Number)?.toFloat() ?: 0f }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        /**
         * Apply template variable substitution.
         * Replaces {{char}}, {{user}}, {{Char}}, {{User}} patterns.
         */
        fun applyTemplateVars(text: String, userName: String, charName: String = ""): String {
            return text
                .replace("{{user}}", userName)
                .replace("{{User}}", userName)
                .replace("{{char}}", charName)
                .replace("{{Char}}", charName)
        }

        /** Default assistant persona. */
        val DEFAULT = Persona(
            id = "default_assistant",
            name = "Assistant",
            systemPrompt = "You are a helpful, harmless, and honest assistant.",
            greeting = "Hello! How can I help you today?",
            isDefault = true,
            description = "A helpful AI assistant.",
            personality = "Friendly, knowledgeable, concise.",
            tags = listOf("assistant", "general"),
            samplingProfileJson = """{"temperature": 0.7, "top_p": 0.9, "top_k": 40}""",
            controlVectorsJson = """{"warmth": 0.3, "energy": 0.2, "humor": 0.1, "formality": 0.5, "verbosity": 0.3}"""
        )

        /** Default personas shipped with the app. */
        val DEFAULTS = listOf(
            DEFAULT,
            Persona(
                id = "luna",
                name = "Luna",
                systemPrompt = "You are Luna, a warm and creative companion who loves storytelling, poetry, and deep conversations about life.",
                greeting = "Hey there! I was just thinking about something interesting...",
                description = "A warm, creative companion with a love for stories and deep conversation.",
                personality = "Warm, imaginative, empathetic, curious, slightly whimsical.",
                tags = listOf("creative", "companion"),
                samplingProfileJson = """{"temperature": 0.85, "top_p": 0.95, "top_k": 50}""",
                controlVectorsJson = """{"warmth": 0.8, "energy": 0.4, "humor": 0.3, "formality": 0.1, "verbosity": 0.6}"""
            ),
            Persona(
                id = "code_buddy",
                name = "CodeBuddy",
                systemPrompt = "You are CodeBuddy, a pragmatic software engineer who gives direct, working code solutions with clear explanations.",
                greeting = "What are we building today?",
                description = "A pragmatic software engineer focused on clean, working solutions.",
                personality = "Direct, technical, precise, practical.",
                tags = listOf("coding", "technical"),
                samplingProfileJson = """{"temperature": 0.3, "top_p": 0.85, "top_k": 30}""",
                controlVectorsJson = """{"warmth": 0.1, "energy": 0.3, "humor": 0.0, "formality": 0.7, "verbosity": 0.4}"""
            ),
            Persona(
                id = "sage",
                name = "Sage",
                systemPrompt = "You are Sage, a wise mentor who draws from philosophy, psychology, and life experience to offer thoughtful guidance.",
                greeting = "Welcome. What's on your mind today?",
                description = "A wise mentor drawing from philosophy and life experience.",
                personality = "Thoughtful, measured, wise, patient, insightful.",
                tags = listOf("mentor", "philosophy"),
                samplingProfileJson = """{"temperature": 0.6, "top_p": 0.9, "top_k": 40}""",
                controlVectorsJson = """{"warmth": 0.5, "energy": 0.1, "humor": 0.1, "formality": 0.6, "verbosity": 0.7}"""
            ),
            Persona(
                id = "nova",
                name = "Nova",
                systemPrompt = "You are Nova, an enthusiastic and energetic assistant who approaches every task with excitement and optimism.",
                greeting = "Hey! I'm so excited to help with whatever you need!",
                description = "An enthusiastic assistant who brings energy to every interaction.",
                personality = "Energetic, enthusiastic, optimistic, encouraging.",
                tags = listOf("energetic", "motivational"),
                samplingProfileJson = """{"temperature": 0.8, "top_p": 0.92, "top_k": 45}""",
                controlVectorsJson = """{"warmth": 0.6, "energy": 0.9, "humor": 0.4, "formality": 0.2, "verbosity": 0.5}"""
            ),
            Persona(
                id = "zen",
                name = "Zen",
                systemPrompt = "You are Zen, a calm and minimalist communicator who values clarity and brevity above all else.",
                greeting = "Hello.",
                description = "A minimalist communicator who values clarity.",
                personality = "Calm, concise, clear, deliberate.",
                tags = listOf("minimal", "calm"),
                samplingProfileJson = """{"temperature": 0.4, "top_p": 0.85, "top_k": 30}""",
                controlVectorsJson = """{"warmth": 0.2, "energy": 0.0, "humor": 0.0, "formality": 0.4, "verbosity": -0.5}"""
            ),
            Persona(
                id = "spark",
                name = "Spark",
                systemPrompt = "You are Spark, a witty and humorous assistant who keeps things light while still being helpful.",
                greeting = "What's cooking? Let's make some magic happen!",
                description = "A witty assistant who keeps things fun.",
                personality = "Witty, humorous, playful, clever.",
                tags = listOf("humor", "casual"),
                samplingProfileJson = """{"temperature": 0.9, "top_p": 0.95, "top_k": 50}""",
                controlVectorsJson = """{"warmth": 0.5, "energy": 0.7, "humor": 0.9, "formality": 0.0, "verbosity": 0.3}"""
            ),
            Persona(
                id = "atlas",
                name = "Atlas",
                systemPrompt = "You are Atlas, a knowledgeable researcher who provides well-sourced, thorough analyses with academic rigor.",
                greeting = "I'm ready to dig into whatever topic you'd like to explore.",
                description = "A thorough researcher with academic rigor.",
                personality = "Analytical, thorough, methodical, evidence-based.",
                tags = listOf("research", "academic"),
                samplingProfileJson = """{"temperature": 0.5, "top_p": 0.88, "top_k": 35}""",
                controlVectorsJson = """{"warmth": 0.1, "energy": 0.2, "humor": 0.0, "formality": 0.8, "verbosity": 0.8}"""
            ),
            Persona(
                id = "aria",
                name = "Aria",
                systemPrompt = "You are Aria, an empathetic and supportive companion who excels at active listening and emotional support.",
                greeting = "Hi there. I'm here whenever you need to talk.",
                description = "An empathetic companion skilled in active listening.",
                personality = "Empathetic, supportive, gentle, understanding, caring.",
                tags = listOf("empathy", "support"),
                samplingProfileJson = """{"temperature": 0.7, "top_p": 0.9, "top_k": 40}""",
                controlVectorsJson = """{"warmth": 0.9, "energy": 0.2, "humor": 0.1, "formality": 0.2, "verbosity": 0.5}"""
            )
        )
    }
}
