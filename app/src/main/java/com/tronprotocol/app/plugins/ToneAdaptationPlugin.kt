package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Adjust communication style based on channel and recipient.
 *
 * Commands:
 *   set_tone|channel|tone    – Set tone for channel (formal, casual, concise, friendly, technical)
 *   get_tone|channel         – Get configured tone for channel
 *   adapt|channel|message    – Adapt message to channel's tone
 *   list                     – List all channel-tone mappings
 *   remove|channel           – Remove tone for channel
 */
class ToneAdaptationPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Tone Adaptation"
    override val description: String =
        "Adapt communication tone. Commands: set_tone|channel|tone, get_tone|channel, adapt|channel|message, list, remove|channel"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set_tone" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: set_tone|channel|tone", elapsed(start))
                    val channel = parts[1].trim()
                    val tone = parts[2].trim().lowercase()
                    if (tone !in VALID_TONES) {
                        return PluginResult.error("Invalid tone. Use: ${VALID_TONES.joinToString()}", elapsed(start))
                    }
                    prefs.edit().putString("tone_$channel", tone).apply()
                    PluginResult.success("Tone for $channel set to $tone", elapsed(start))
                }
                "get_tone" -> {
                    val channel = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: get_tone|channel", elapsed(start))
                    val tone = prefs.getString("tone_$channel", "neutral") ?: "neutral"
                    PluginResult.success("Tone for $channel: $tone", elapsed(start))
                }
                "adapt" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: adapt|channel|message", elapsed(start))
                    val channel = parts[1].trim()
                    val message = parts[2].trim()
                    val tone = prefs.getString("tone_$channel", "neutral") ?: "neutral"
                    val adapted = adaptMessage(message, tone)
                    PluginResult.success(JSONObject().apply {
                        put("original", message)
                        put("tone", tone)
                        put("adapted", adapted)
                        put("guidelines", getToneGuidelines(tone))
                    }.toString(2), elapsed(start))
                }
                "list" -> {
                    val mappings = JSONObject()
                    prefs.all.forEach { (key, value) ->
                        if (key.startsWith("tone_")) {
                            mappings.put(key.removePrefix("tone_"), value)
                        }
                    }
                    PluginResult.success("Tone mappings:\n${mappings.toString(2)}", elapsed(start))
                }
                "remove" -> {
                    val channel = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: remove|channel", elapsed(start))
                    prefs.edit().remove("tone_$channel").apply()
                    PluginResult.success("Removed tone for $channel", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Tone adaptation error: ${e.message}", elapsed(start))
        }
    }

    private fun adaptMessage(message: String, tone: String): String {
        // Provide adaptation hints (actual rewriting would use LLM)
        return when (tone) {
            "formal" -> "[Formal] $message"
            "casual" -> "[Casual] $message"
            "concise" -> message.split(". ").first() + "."
            "friendly" -> "[Friendly] $message"
            "technical" -> "[Technical] $message"
            else -> message
        }
    }

    private fun getToneGuidelines(tone: String): String = when (tone) {
        "formal" -> "Use complete sentences, proper grammar, avoid contractions, be professional."
        "casual" -> "Relaxed language, contractions OK, conversational, friendly but clear."
        "concise" -> "Minimal words, no filler, direct statements, bullet points preferred."
        "friendly" -> "Warm, supportive, use first-person, show empathy."
        "technical" -> "Precise terminology, include specifics, data-driven, reference sources."
        else -> "No specific guidelines."
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("tone_adaptation", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "tone_adaptation"
        private val VALID_TONES = setOf("formal", "casual", "concise", "friendly", "technical", "neutral")
    }
}
