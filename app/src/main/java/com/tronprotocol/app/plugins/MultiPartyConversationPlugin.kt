package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Track multiple ongoing conversations across channels with context continuity.
 * Links related conversations across different platforms.
 *
 * Commands:
 *   track|topic|channel|participant   – Register a conversation thread
 *   link|topic1|topic2               – Link two topics as related
 *   context|topic                    – Get full cross-channel context for a topic
 *   active                           – List all active conversation topics
 *   add_note|topic|note              – Add context note to a topic
 *   close|topic                      – Mark topic as resolved
 */
class MultiPartyConversationPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Multi-Party Conversation"
    override val description: String =
        "Cross-channel conversation tracking. Commands: track|topic|channel|participant, link|topic1|topic2, context|topic, active, add_note|topic|note, close|topic"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "track" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: track|topic|channel|participant", elapsed(start))
                    val topic = parts[1].trim()
                    val threads = loadThreads()
                    val existing = findThread(threads, topic) ?: JSONObject().apply {
                        put("topic", topic)
                        put("channels", JSONArray())
                        put("participants", JSONArray())
                        put("notes", JSONArray())
                        put("links", JSONArray())
                        put("status", "active")
                        put("created", System.currentTimeMillis())
                    }
                    val channels = existing.getJSONArray("channels")
                    val channel = parts[2].trim()
                    if (!jsonArrayContains(channels, channel)) channels.put(channel)
                    val participants = existing.getJSONArray("participants")
                    val participant = parts[3].trim()
                    if (!jsonArrayContains(participants, participant)) participants.put(participant)
                    existing.put("updated", System.currentTimeMillis())
                    replaceThread(threads, topic, existing)
                    saveThreads(threads)
                    PluginResult.success("Tracking $topic on $channel with $participant", elapsed(start))
                }
                "link" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: link|topic1|topic2", elapsed(start))
                    val threads = loadThreads()
                    val t1 = findThread(threads, parts[1].trim())
                    val t2 = findThread(threads, parts[2].trim())
                    if (t1 == null || t2 == null) {
                        return PluginResult.error("Both topics must exist", elapsed(start))
                    }
                    val links1 = t1.getJSONArray("links")
                    if (!jsonArrayContains(links1, parts[2].trim())) links1.put(parts[2].trim())
                    val links2 = t2.getJSONArray("links")
                    if (!jsonArrayContains(links2, parts[1].trim())) links2.put(parts[1].trim())
                    replaceThread(threads, parts[1].trim(), t1)
                    replaceThread(threads, parts[2].trim(), t2)
                    saveThreads(threads)
                    PluginResult.success("Linked: ${parts[1].trim()} <-> ${parts[2].trim()}", elapsed(start))
                }
                "context" -> {
                    val topic = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: context|topic", elapsed(start))
                    val threads = loadThreads()
                    val thread = findThread(threads, topic)
                        ?: return PluginResult.error("Topic not found: $topic", elapsed(start))
                    PluginResult.success(thread.toString(2), elapsed(start))
                }
                "active" -> {
                    val threads = loadThreads()
                    val active = JSONArray()
                    for (i in 0 until threads.length()) {
                        val t = threads.getJSONObject(i)
                        if (t.optString("status") == "active") {
                            active.put(JSONObject().apply {
                                put("topic", t.optString("topic"))
                                put("channels", t.optJSONArray("channels"))
                                put("participants", t.optJSONArray("participants"))
                            })
                        }
                    }
                    PluginResult.success("Active conversations (${active.length()}):\n${active.toString(2)}", elapsed(start))
                }
                "add_note" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: add_note|topic|note", elapsed(start))
                    val threads = loadThreads()
                    val thread = findThread(threads, parts[1].trim())
                        ?: return PluginResult.error("Topic not found", elapsed(start))
                    thread.getJSONArray("notes").put(JSONObject().apply {
                        put("note", parts[2].trim())
                        put("timestamp", System.currentTimeMillis())
                    })
                    replaceThread(threads, parts[1].trim(), thread)
                    saveThreads(threads)
                    PluginResult.success("Note added to ${parts[1].trim()}", elapsed(start))
                }
                "close" -> {
                    val topic = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: close|topic", elapsed(start))
                    val threads = loadThreads()
                    val thread = findThread(threads, topic)
                        ?: return PluginResult.error("Topic not found", elapsed(start))
                    thread.put("status", "closed")
                    thread.put("closed_at", System.currentTimeMillis())
                    replaceThread(threads, topic, thread)
                    saveThreads(threads)
                    PluginResult.success("Closed: $topic", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Multi-party error: ${e.message}", elapsed(start))
        }
    }

    private fun findThread(threads: JSONArray, topic: String): JSONObject? {
        for (i in 0 until threads.length()) {
            val t = threads.getJSONObject(i)
            if (t.optString("topic") == topic) return t
        }
        return null
    }

    private fun replaceThread(threads: JSONArray, topic: String, thread: JSONObject) {
        for (i in 0 until threads.length()) {
            if (threads.getJSONObject(i).optString("topic") == topic) {
                threads.put(i, thread)
                return
            }
        }
        threads.put(thread)
    }

    private fun jsonArrayContains(arr: JSONArray, value: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == value) return true
        }
        return false
    }

    private fun loadThreads(): JSONArray {
        val str = prefs.getString("threads", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun saveThreads(arr: JSONArray) = prefs.edit().putString("threads", arr.toString()).apply()

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("multi_party_conv", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "multi_party_conversation"
    }
}
