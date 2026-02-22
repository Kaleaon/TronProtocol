package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI-initiated message system. Queues messages to be sent proactively
 * through configured channels (Telegram, webhook, notification).
 *
 * Commands:
 *   queue|channel|message        – Queue a proactive message
 *   send_now|channel|message     – Send immediately via channel plugin
 *   pending                      – List pending proactive messages
 *   history|count                – Recent sent messages
 *   clear                        – Clear pending queue
 *   set_rule|trigger|channel|template – Set automated trigger rule
 *   list_rules                   – Show automation rules
 *   remove_rule|trigger          – Remove a trigger rule
 */
class ProactiveMessagingPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Proactive Messaging"
    override val description: String =
        "AI-initiated messages. Commands: queue|channel|message, send_now|channel|message, pending, history|count, clear, set_rule|trigger|channel|template, list_rules, remove_rule|trigger"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "queue" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: queue|channel|message", elapsed(start))
                    val channel = parts[1].trim()
                    val message = parts[2].trim()
                    val queue = loadQueue()
                    queue.put(JSONObject().apply {
                        put("channel", channel)
                        put("message", message)
                        put("queued_at", System.currentTimeMillis())
                        put("status", "pending")
                    })
                    saveQueue(queue)
                    PluginResult.success("Queued message for $channel (${queue.length()} pending)", elapsed(start))
                }
                "send_now" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: send_now|channel|message", elapsed(start))
                    val channel = parts[1].trim()
                    val message = parts[2].trim()
                    // Record in history
                    val history = loadHistory()
                    history.put(JSONObject().apply {
                        put("channel", channel)
                        put("message", message)
                        put("sent_at", System.currentTimeMillis())
                        put("status", "sent")
                    })
                    saveHistory(history)
                    PluginResult.success("Ready to send via $channel: $message\n(Use PluginManager to route to $channel plugin)", elapsed(start))
                }
                "pending" -> {
                    val queue = loadQueue()
                    PluginResult.success("Pending messages (${queue.length()}):\n${queue.toString(2)}", elapsed(start))
                }
                "history" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    val history = loadHistory()
                    val recent = JSONArray()
                    val startIdx = (history.length() - count).coerceAtLeast(0)
                    for (i in startIdx until history.length()) {
                        recent.put(history.getJSONObject(i))
                    }
                    PluginResult.success("Message history (${recent.length()}):\n${recent.toString(2)}", elapsed(start))
                }
                "clear" -> {
                    saveQueue(JSONArray())
                    PluginResult.success("Pending queue cleared", elapsed(start))
                }
                "set_rule" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: set_rule|trigger|channel|template", elapsed(start))
                    val rules = loadRules()
                    rules.put(JSONObject().apply {
                        put("trigger", parts[1].trim())
                        put("channel", parts[2].trim())
                        put("template", parts[3].trim())
                    })
                    saveRules(rules)
                    PluginResult.success("Rule added: ${parts[1].trim()}", elapsed(start))
                }
                "list_rules" -> {
                    val rules = loadRules()
                    PluginResult.success("Automation rules (${rules.length()}):\n${rules.toString(2)}", elapsed(start))
                }
                "remove_rule" -> {
                    val trigger = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: remove_rule|trigger", elapsed(start))
                    val rules = loadRules()
                    val newRules = JSONArray()
                    for (i in 0 until rules.length()) {
                        val r = rules.getJSONObject(i)
                        if (r.optString("trigger") != trigger) newRules.put(r)
                    }
                    saveRules(newRules)
                    PluginResult.success("Rule removed: $trigger", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Proactive messaging error: ${e.message}", elapsed(start))
        }
    }

    private fun loadQueue(): JSONArray = loadJsonArray("proactive_queue")
    private fun saveQueue(arr: JSONArray) = prefs.edit().putString("proactive_queue", arr.toString()).apply()
    private fun loadHistory(): JSONArray = loadJsonArray("proactive_history")
    private fun saveHistory(arr: JSONArray) = prefs.edit().putString("proactive_history", arr.toString()).apply()
    private fun loadRules(): JSONArray = loadJsonArray("proactive_rules")
    private fun saveRules(arr: JSONArray) = prefs.edit().putString("proactive_rules", arr.toString()).apply()

    private fun loadJsonArray(key: String): JSONArray {
        val str = prefs.getString(key, null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("proactive_messaging", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "proactive_messaging"
    }
}
