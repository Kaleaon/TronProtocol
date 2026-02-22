package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cron-like recurring actions scheduler.
 * Defines actions that execute periodically via the heartbeat loop.
 *
 * Commands:
 *   add|name|interval_min|plugin|input  – Add recurring action
 *   list                                 – List all scheduled actions
 *   remove|name                          – Remove a scheduled action
 *   check_due                            – Get actions due for execution now
 *   pause|name                           – Pause a scheduled action
 *   resume|name                          – Resume a paused action
 *   history|count                        – Recent execution history
 */
class ScheduledActionsPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Scheduled Actions"
    override val description: String =
        "Cron-like recurring actions. Commands: add|name|interval_min|plugin|input, list, remove|name, check_due, pause|name, resume|name, history|count"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 5)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add" -> {
                    if (parts.size < 5) return PluginResult.error("Usage: add|name|interval_min|plugin|input", elapsed(start))
                    val actions = loadActions()
                    val name = parts[1].trim()
                    actions.put(JSONObject().apply {
                        put("name", name)
                        put("interval_ms", parts[2].trim().toLong() * 60000)
                        put("plugin", parts[3].trim())
                        put("input", parts[4].trim())
                        put("status", "active")
                        put("last_run", 0L)
                        put("next_run", System.currentTimeMillis() + parts[2].trim().toLong() * 60000)
                        put("run_count", 0)
                        put("created", System.currentTimeMillis())
                    })
                    saveActions(actions)
                    PluginResult.success("Scheduled '$name' every ${parts[2].trim()} min: ${parts[3].trim()} ${parts[4].trim()}", elapsed(start))
                }
                "list" -> {
                    val actions = loadActions()
                    PluginResult.success("Scheduled actions (${actions.length()}):\n${actions.toString(2)}", elapsed(start))
                }
                "remove" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: remove|name", elapsed(start))
                    val actions = loadActions()
                    val filtered = JSONArray()
                    for (i in 0 until actions.length()) {
                        val a = actions.getJSONObject(i)
                        if (a.optString("name") != name) filtered.put(a)
                    }
                    saveActions(filtered)
                    PluginResult.success("Removed: $name", elapsed(start))
                }
                "check_due" -> {
                    val now = System.currentTimeMillis()
                    val actions = loadActions()
                    val due = JSONArray()
                    for (i in 0 until actions.length()) {
                        val a = actions.getJSONObject(i)
                        if (a.optString("status") == "active" && a.optLong("next_run", Long.MAX_VALUE) <= now) {
                            due.put(a)
                            a.put("last_run", now)
                            a.put("next_run", now + a.optLong("interval_ms", 3600000))
                            a.put("run_count", a.optInt("run_count", 0) + 1)
                        }
                    }
                    saveActions(actions)
                    PluginResult.success("Due actions (${due.length()}):\n${due.toString(2)}", elapsed(start))
                }
                "pause" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: pause|name", elapsed(start))
                    val actions = loadActions()
                    for (i in 0 until actions.length()) {
                        val a = actions.getJSONObject(i)
                        if (a.optString("name") == name) a.put("status", "paused")
                    }
                    saveActions(actions)
                    PluginResult.success("Paused: $name", elapsed(start))
                }
                "resume" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: resume|name", elapsed(start))
                    val actions = loadActions()
                    for (i in 0 until actions.length()) {
                        val a = actions.getJSONObject(i)
                        if (a.optString("name") == name) {
                            a.put("status", "active")
                            a.put("next_run", System.currentTimeMillis() + a.optLong("interval_ms", 3600000))
                        }
                    }
                    saveActions(actions)
                    PluginResult.success("Resumed: $name", elapsed(start))
                }
                "history" -> {
                    val history = loadHistory()
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    val recent = JSONArray()
                    val startIdx = (history.length() - count).coerceAtLeast(0)
                    for (i in startIdx until history.length()) recent.put(history.getJSONObject(i))
                    PluginResult.success("Execution history (${recent.length()}):\n${recent.toString(2)}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Scheduler error: ${e.message}", elapsed(start))
        }
    }

    private fun loadActions(): JSONArray {
        val str = prefs.getString("actions", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun saveActions(arr: JSONArray) = prefs.edit().putString("actions", arr.toString()).apply()

    private fun loadHistory(): JSONArray {
        val str = prefs.getString("exec_history", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("scheduled_actions", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "scheduled_actions"
    }
}
