package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Audit Dashboard Plugin - Phase 8 Safety & Transparency.
 *
 * Provides a summary dashboard of all system activity for transparency.
 * Every plugin action can be logged here and queried by plugin, time range,
 * or keyword.
 *
 * Commands:
 *   log|plugin_id|action|detail   - Record an audit entry
 *   recent|count                  - Show the most recent N entries
 *   by_plugin|plugin_id           - Show entries for a specific plugin
 *   summary                       - Counts per plugin, total actions, timespan
 *   search|keyword                - Search entries by keyword
 *   clear                         - Delete all audit entries
 *   export                        - Export all logs as a JSON array
 */
class AuditDashboardPlugin : Plugin {

    companion object {
        private const val ID = "audit_dashboard"
        private const val PREFS_NAME = "tronprotocol_audit_dashboard"
        private const val KEY_ENTRIES = "audit_entries"
    }

    private lateinit var prefs: SharedPreferences
    private val entries = mutableListOf<JSONObject>()

    override val id: String = ID
    override val name: String = "Audit Dashboard"
    override val description: String =
        "System activity dashboard. Commands: log|plugin_id|action|detail, " +
            "recent|count, by_plugin|plugin_id, summary, search|keyword, clear, export"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "log" -> handleLog(parts, start)
                "recent" -> handleRecent(parts, start)
                "by_plugin" -> handleByPlugin(parts, start)
                "summary" -> handleSummary(start)
                "search" -> handleSearch(parts, start)
                "clear" -> handleClear(start)
                "export" -> handleExport(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: log, recent, by_plugin, summary, search, clear, export",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Audit dashboard error: ${e.message}", elapsed(start))
        }
    }

    private fun handleLog(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4) {
            return PluginResult.error("Usage: log|plugin_id|action|detail", elapsed(start))
        }
        val entry = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("plugin_id", parts[1].trim())
            put("action", parts[2].trim())
            put("detail", parts[3].trim())
        }
        entries.add(entry)
        save()
        return PluginResult.success(
            "Logged: [${parts[1].trim()}] ${parts[2].trim()} - ${parts[3].trim()}",
            elapsed(start)
        )
    }

    private fun handleRecent(parts: List<String>, start: Long): PluginResult {
        val count = if (parts.size >= 2) parts[1].trim().toIntOrNull() ?: 10 else 10
        if (entries.isEmpty()) {
            return PluginResult.success("No audit entries.", elapsed(start))
        }
        val recent = entries.takeLast(count)
        val sb = buildString {
            append("Recent ${recent.size} audit entries:\n")
            recent.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("plugin_id")}] ")
                append("${entry.optString("action")} - ${entry.optString("detail")} ")
                append("(${formatTimestamp(entry.optLong("timestamp"))})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleByPlugin(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: by_plugin|plugin_id", elapsed(start))
        }
        val pluginId = parts[1].trim()
        val filtered = entries.filter { it.optString("plugin_id") == pluginId }
        if (filtered.isEmpty()) {
            return PluginResult.success("No entries for plugin '$pluginId'.", elapsed(start))
        }
        val sb = buildString {
            append("Entries for '$pluginId' (${filtered.size}):\n")
            filtered.forEachIndexed { i, entry ->
                append("${i + 1}. ${entry.optString("action")} - ${entry.optString("detail")} ")
                append("(${formatTimestamp(entry.optLong("timestamp"))})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleSummary(start: Long): PluginResult {
        if (entries.isEmpty()) {
            return PluginResult.success("No audit entries recorded.", elapsed(start))
        }
        val byPlugin = entries.groupBy { it.optString("plugin_id") }
        val earliest = entries.minOf { it.optLong("timestamp") }
        val latest = entries.maxOf { it.optLong("timestamp") }
        val spanMs = latest - earliest
        val spanMinutes = spanMs / 60000

        val sb = buildString {
            append("Audit Summary:\n")
            append("Total entries: ${entries.size}\n")
            append("Timespan: ${spanMinutes}m (${formatTimestamp(earliest)} to ${formatTimestamp(latest)})\n")
            append("Entries by plugin:\n")
            byPlugin.entries.sortedByDescending { it.value.size }.forEach { (plugin, list) ->
                append("  $plugin: ${list.size}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleSearch(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: search|keyword", elapsed(start))
        }
        val keyword = parts[1].trim().lowercase()
        val matches = entries.filter { entry ->
            entry.optString("action").lowercase().contains(keyword) ||
                entry.optString("detail").lowercase().contains(keyword) ||
                entry.optString("plugin_id").lowercase().contains(keyword)
        }
        if (matches.isEmpty()) {
            return PluginResult.success("No entries matching '$keyword'.", elapsed(start))
        }
        val sb = buildString {
            append("Found ${matches.size} entries matching '$keyword':\n")
            matches.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("plugin_id")}] ")
                append("${entry.optString("action")} - ${entry.optString("detail")}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleClear(start: Long): PluginResult {
        val count = entries.size
        entries.clear()
        save()
        return PluginResult.success("Cleared $count audit entries.", elapsed(start))
    }

    private fun handleExport(start: Long): PluginResult {
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        return PluginResult.success(arr.toString(), elapsed(start))
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts == 0L) return "unknown"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ts))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun save() {
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun load() {
        val data = prefs.getString(KEY_ENTRIES, null) ?: return
        try {
            val arr = JSONArray(data)
            entries.clear()
            for (i in 0 until arr.length()) {
                entries.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) { }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    override fun destroy() {
        entries.clear()
    }
}
