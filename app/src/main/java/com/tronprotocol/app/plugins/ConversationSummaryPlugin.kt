package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Summarize long-running conversations to prevent context loss.
 *
 * Commands:
 *   add|channel|speaker|message   – Add a message to a conversation
 *   summarize|channel             – Generate summary for a channel
 *   get|channel                   – Get conversation history for a channel
 *   channels                      – List active conversation channels
 *   clear|channel                 – Clear a conversation
 */
class ConversationSummaryPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Conversation Summary"
    override val description: String =
        "Summarize conversations. Commands: add|channel|speaker|message, summarize|channel, get|channel, channels, clear|channel"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: add|channel|speaker|message", elapsed(start))
                    val channel = parts[1].trim()
                    val conv = loadConversation(channel)
                    conv.put(JSONObject().apply {
                        put("speaker", parts[2].trim())
                        put("message", parts[3].trim())
                        put("timestamp", System.currentTimeMillis())
                    })
                    saveConversation(channel, conv)
                    PluginResult.success("Added to $channel (${conv.length()} messages)", elapsed(start))
                }
                "summarize" -> {
                    val channel = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: summarize|channel", elapsed(start))
                    val conv = loadConversation(channel)
                    if (conv.length() == 0) {
                        return PluginResult.success("No messages in $channel", elapsed(start))
                    }
                    val summary = generateSummary(channel, conv)
                    PluginResult.success(summary, elapsed(start))
                }
                "get" -> {
                    val channel = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: get|channel", elapsed(start))
                    val conv = loadConversation(channel)
                    PluginResult.success("$channel (${conv.length()} messages):\n${conv.toString(2)}", elapsed(start))
                }
                "channels" -> {
                    val channels = prefs.all.keys.filter { it.startsWith("conv_") }
                        .map { it.removePrefix("conv_") }
                    PluginResult.success("Active channels: ${channels.joinToString(", ")}", elapsed(start))
                }
                "clear" -> {
                    val channel = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: clear|channel", elapsed(start))
                    prefs.edit().remove("conv_$channel").apply()
                    PluginResult.success("Cleared $channel", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Conversation summary error: ${e.message}", elapsed(start))
        }
    }

    private fun generateSummary(channel: String, conv: JSONArray): String {
        val speakers = mutableSetOf<String>()
        val topics = mutableMapOf<String, Int>()
        var totalWords = 0

        for (i in 0 until conv.length()) {
            val msg = conv.getJSONObject(i)
            speakers.add(msg.optString("speaker", "unknown"))
            val words = msg.optString("message", "").split("\\s+".toRegex())
            totalWords += words.size
            for (word in words) {
                if (word.length > 4) {
                    val w = word.lowercase()
                    topics[w] = (topics[w] ?: 0) + 1
                }
            }
        }

        val topTopics = topics.entries.sortedByDescending { it.value }.take(10).map { it.key }
        val first = conv.getJSONObject(0)
        val last = conv.getJSONObject(conv.length() - 1)

        return buildString {
            append("Summary of $channel:\n")
            append("Messages: ${conv.length()}\n")
            append("Participants: ${speakers.joinToString(", ")}\n")
            append("Total words: $totalWords\n")
            append("Key topics: ${topTopics.joinToString(", ")}\n")
            append("Started: ${first.optLong("timestamp")}\n")
            append("Last message: ${last.optLong("timestamp")}\n")
            append("Last speaker: ${last.optString("speaker")}\n")
            append("Last message preview: ${last.optString("message").take(200)}")
        }
    }

    private fun loadConversation(channel: String): JSONArray {
        val str = prefs.getString("conv_$channel", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun saveConversation(channel: String, conv: JSONArray) {
        // Keep last 200 messages per channel
        while (conv.length() > 200) conv.remove(0)
        prefs.edit().putString("conv_$channel", conv.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("conv_summary", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "conversation_summary"
    }
}
