package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Action Explanation Plugin - Phase 8 Safety & Transparency.
 *
 * Records and provides explanations for why the AI took specific actions.
 * Each explanation captures the action identifier, a human-readable description,
 * the reasoning chain, the outcome, and a timestamp.
 *
 * Commands:
 *   record|action_id|action_desc|reasoning|outcome  - Record an explained action
 *   explain|action_id                                - Retrieve the full reasoning for an action
 *   recent|count                                     - Show the most recent N explanations
 *   search|keyword                                   - Search explanations by keyword
 *   stats                                            - Total explained actions, most common types
 */
class ActionExplanationPlugin : Plugin {

    companion object {
        private const val ID = "action_explanation"
        private const val PREFS_NAME = "tronprotocol_action_explanation"
        private const val KEY_EXPLANATIONS = "explanations"
    }

    private lateinit var prefs: SharedPreferences
    private val explanations = mutableListOf<JSONObject>()

    override val id: String = ID
    override val name: String = "Action Explanation"
    override val description: String =
        "AI action explanation system. Commands: record|action_id|action_desc|reasoning|outcome, " +
            "explain|action_id, recent|count, search|keyword, stats"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 5)
            val command = parts[0].trim().lowercase()

            when (command) {
                "record" -> handleRecord(parts, start)
                "explain" -> handleExplain(parts, start)
                "recent" -> handleRecent(parts, start)
                "search" -> handleSearch(parts, start)
                "stats" -> handleStats(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: record, explain, recent, search, stats",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Action explanation error: ${e.message}", elapsed(start))
        }
    }

    private fun handleRecord(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 5) {
            return PluginResult.error(
                "Usage: record|action_id|action_desc|reasoning|outcome",
                elapsed(start)
            )
        }
        val actionId = parts[1].trim()
        val actionDesc = parts[2].trim()
        val reasoning = parts[3].trim()
        val outcome = parts[4].trim()

        // Check for duplicate action_id
        val existing = explanations.indexOfFirst { it.optString("action_id") == actionId }
        if (existing >= 0) {
            // Update existing explanation
            explanations[existing] = JSONObject().apply {
                put("action_id", actionId)
                put("description", actionDesc)
                put("reasoning", reasoning)
                put("outcome", outcome)
                put("timestamp", System.currentTimeMillis())
            }
            save()
            return PluginResult.success(
                "Updated explanation for action '$actionId': $actionDesc",
                elapsed(start)
            )
        }

        val entry = JSONObject().apply {
            put("action_id", actionId)
            put("description", actionDesc)
            put("reasoning", reasoning)
            put("outcome", outcome)
            put("timestamp", System.currentTimeMillis())
        }
        explanations.add(entry)
        save()
        return PluginResult.success(
            "Recorded explanation for action '$actionId': $actionDesc -> $outcome",
            elapsed(start)
        )
    }

    private fun handleExplain(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: explain|action_id", elapsed(start))
        }
        val actionId = parts[1].trim()
        val entry = explanations.find { it.optString("action_id") == actionId }
            ?: return PluginResult.success(
                "No explanation found for action '$actionId'.",
                elapsed(start)
            )

        val sb = buildString {
            append("Explanation for action '$actionId':\n")
            append("  Description: ${entry.optString("description")}\n")
            append("  Reasoning: ${entry.optString("reasoning")}\n")
            append("  Outcome: ${entry.optString("outcome")}\n")
            append("  Recorded: ${formatTimestamp(entry.optLong("timestamp"))}")
        }
        return PluginResult.success(sb, elapsed(start))
    }

    private fun handleRecent(parts: List<String>, start: Long): PluginResult {
        val count = if (parts.size >= 2) parts[1].trim().toIntOrNull() ?: 10 else 10
        if (explanations.isEmpty()) {
            return PluginResult.success("No action explanations recorded.", elapsed(start))
        }
        val recent = explanations.takeLast(count)
        val sb = buildString {
            append("Recent ${recent.size} action explanations:\n")
            recent.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("action_id")}] ")
                append("${entry.optString("description")} -> ${entry.optString("outcome")} ")
                append("(${formatTimestamp(entry.optLong("timestamp"))})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleSearch(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: search|keyword", elapsed(start))
        }
        val keyword = parts[1].trim().lowercase()
        val matches = explanations.filter { entry ->
            entry.optString("action_id").lowercase().contains(keyword) ||
                entry.optString("description").lowercase().contains(keyword) ||
                entry.optString("reasoning").lowercase().contains(keyword) ||
                entry.optString("outcome").lowercase().contains(keyword)
        }
        if (matches.isEmpty()) {
            return PluginResult.success("No explanations matching '$keyword'.", elapsed(start))
        }
        val sb = buildString {
            append("Found ${matches.size} explanations matching '$keyword':\n")
            matches.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("action_id")}] ")
                append("${entry.optString("description")} -> ${entry.optString("outcome")}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleStats(start: Long): PluginResult {
        if (explanations.isEmpty()) {
            return PluginResult.success("No action explanations recorded.", elapsed(start))
        }

        val total = explanations.size

        // Group by action description prefix (first word) to find common action types
        val actionTypes = explanations.groupBy { entry ->
            val desc = entry.optString("description", "")
            desc.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: "unknown"
        }
        val sortedTypes = actionTypes.entries.sortedByDescending { it.value.size }

        // Outcome distribution
        val outcomes = explanations.groupBy { it.optString("outcome", "unknown").lowercase() }
        val sortedOutcomes = outcomes.entries.sortedByDescending { it.value.size }

        val earliest = explanations.minOf { it.optLong("timestamp") }
        val latest = explanations.maxOf { it.optLong("timestamp") }

        val sb = buildString {
            append("Action Explanation Stats:\n")
            append("  Total explained actions: $total\n")
            append("  Timespan: ${formatTimestamp(earliest)} to ${formatTimestamp(latest)}\n")
            append("  Most common action types:\n")
            sortedTypes.take(5).forEach { (type, list) ->
                append("    $type: ${list.size}\n")
            }
            append("  Outcome distribution:\n")
            sortedOutcomes.take(5).forEach { (outcome, list) ->
                append("    $outcome: ${list.size}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts == 0L) return "unknown"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ts))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun save() {
        val arr = JSONArray()
        explanations.forEach { arr.put(it) }
        prefs.edit().putString(KEY_EXPLANATIONS, arr.toString()).apply()
    }

    private fun load() {
        val data = prefs.getString(KEY_EXPLANATIONS, null) ?: return
        try {
            val arr = JSONArray(data)
            explanations.clear()
            for (i in 0 until arr.length()) {
                explanations.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) { }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    override fun destroy() {
        explanations.clear()
    }
}
