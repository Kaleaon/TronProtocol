package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queue messages for future delivery. Supports one-time and recurring schedules.
 *
 * Commands:
 *   schedule|channel|time_ms|message          – Schedule one-time message
 *   recurring|channel|interval_ms|message     – Schedule recurring message
 *   list                                       – List all scheduled messages
 *   cancel|schedule_id                         – Cancel a scheduled message
 *   due                                        – Get messages due for delivery now
 *   clear                                      – Clear all schedules
 */
class CommunicationSchedulerPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Communication Scheduler"
    override val description: String =
        "Schedule future messages. Commands: schedule|channel|time_ms|message, recurring|channel|interval_ms|message, list, cancel|id, due, clear"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "schedule" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: schedule|channel|time_ms|message", elapsed(start))
                    val schedules = loadSchedules()
                    val id = "sched_${System.currentTimeMillis()}"
                    schedules.put(JSONObject().apply {
                        put("id", id)
                        put("channel", parts[1].trim())
                        put("deliver_at", parts[2].trim().toLong())
                        put("message", parts[3].trim())
                        put("recurring", false)
                        put("created", System.currentTimeMillis())
                    })
                    saveSchedules(schedules)
                    PluginResult.success("Scheduled: $id for ${parts[1].trim()} at ${parts[2].trim()}", elapsed(start))
                }
                "recurring" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: recurring|channel|interval_ms|message", elapsed(start))
                    val schedules = loadSchedules()
                    val id = "rec_${System.currentTimeMillis()}"
                    schedules.put(JSONObject().apply {
                        put("id", id)
                        put("channel", parts[1].trim())
                        put("interval_ms", parts[2].trim().toLong())
                        put("next_delivery", System.currentTimeMillis() + parts[2].trim().toLong())
                        put("message", parts[3].trim())
                        put("recurring", true)
                        put("created", System.currentTimeMillis())
                    })
                    saveSchedules(schedules)
                    PluginResult.success("Recurring schedule: $id every ${parts[2].trim()}ms", elapsed(start))
                }
                "list" -> {
                    val schedules = loadSchedules()
                    PluginResult.success("Schedules (${schedules.length()}):\n${schedules.toString(2)}", elapsed(start))
                }
                "cancel" -> {
                    val cancelId = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: cancel|id", elapsed(start))
                    val schedules = loadSchedules()
                    val newSchedules = JSONArray()
                    for (i in 0 until schedules.length()) {
                        val s = schedules.getJSONObject(i)
                        if (s.optString("id") != cancelId) newSchedules.put(s)
                    }
                    saveSchedules(newSchedules)
                    PluginResult.success("Cancelled: $cancelId", elapsed(start))
                }
                "due" -> {
                    val now = System.currentTimeMillis()
                    val schedules = loadSchedules()
                    val due = JSONArray()
                    val updated = JSONArray()
                    for (i in 0 until schedules.length()) {
                        val s = schedules.getJSONObject(i)
                        val isRecurring = s.optBoolean("recurring", false)
                        if (isRecurring) {
                            val nextDelivery = s.optLong("next_delivery", Long.MAX_VALUE)
                            if (nextDelivery <= now) {
                                due.put(s)
                                s.put("next_delivery", now + s.optLong("interval_ms", 3600000))
                            }
                            updated.put(s)
                        } else {
                            val deliverAt = s.optLong("deliver_at", Long.MAX_VALUE)
                            if (deliverAt <= now) {
                                due.put(s)
                                // One-time: don't re-add
                            } else {
                                updated.put(s)
                            }
                        }
                    }
                    saveSchedules(updated)
                    PluginResult.success("Due messages (${due.length()}):\n${due.toString(2)}", elapsed(start))
                }
                "clear" -> {
                    saveSchedules(JSONArray())
                    PluginResult.success("All schedules cleared", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Scheduler error: ${e.message}", elapsed(start))
        }
    }

    private fun loadSchedules(): JSONArray {
        val str = prefs.getString("schedules", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun saveSchedules(arr: JSONArray) = prefs.edit().putString("schedules", arr.toString()).apply()

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("comm_scheduler", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "communication_scheduler"
    }
}
