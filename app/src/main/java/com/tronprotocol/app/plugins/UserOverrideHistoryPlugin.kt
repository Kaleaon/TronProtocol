package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * User Override History Plugin - Phase 8 Safety & Transparency.
 *
 * Records all instances where the user overrode AI decisions.
 * Tracks the original AI choice, the user's replacement choice, and the
 * reason for the override.  Provides pattern learning so the AI can
 * improve over time by understanding where it consistently diverges from
 * user preferences.
 *
 * Commands:
 *   record|decision_id|ai_choice|user_choice|reason  - Record an override
 *   list|count                                        - List the most recent N overrides
 *   by_category|category                              - Show overrides in a category
 *   learn|category                                    - Analyze override patterns in a category
 *   stats                                             - Override rates and most common categories
 *   clear                                             - Clear all override records
 */
class UserOverrideHistoryPlugin : Plugin {

    companion object {
        private const val ID = "user_override_history"
        private const val PREFS_NAME = "tronprotocol_user_override_history"
        private const val KEY_OVERRIDES = "overrides"
    }

    private lateinit var prefs: SharedPreferences
    private val overrides = mutableListOf<JSONObject>()

    override val id: String = ID
    override val name: String = "User Override History"
    override val description: String =
        "Tracks user overrides of AI decisions. Commands: record|decision_id|ai_choice|user_choice|reason, " +
            "list|count, by_category|category, learn|category, stats, clear"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 5)
            val command = parts[0].trim().lowercase()

            when (command) {
                "record" -> handleRecord(parts, start)
                "list" -> handleList(parts, start)
                "by_category" -> handleByCategory(parts, start)
                "learn" -> handleLearn(parts, start)
                "stats" -> handleStats(start)
                "clear" -> handleClear(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: record, list, by_category, learn, stats, clear",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("User override history error: ${e.message}", elapsed(start))
        }
    }

    private fun handleRecord(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 5) {
            return PluginResult.error(
                "Usage: record|decision_id|ai_choice|user_choice|reason",
                elapsed(start)
            )
        }
        val decisionId = parts[1].trim()
        val aiChoice = parts[2].trim()
        val userChoice = parts[3].trim()
        val reason = parts[4].trim()

        // Derive a category from the decision_id (use prefix before first underscore or dot)
        val category = decisionId.split("[_.]".toRegex()).firstOrNull()?.lowercase() ?: "general"

        val entry = JSONObject().apply {
            put("decision_id", decisionId)
            put("ai_choice", aiChoice)
            put("user_choice", userChoice)
            put("reason", reason)
            put("category", category)
            put("timestamp", System.currentTimeMillis())
        }
        overrides.add(entry)
        save()

        return PluginResult.success(
            "Override recorded: [$decisionId] AI chose '$aiChoice', user chose '$userChoice'. Reason: $reason",
            elapsed(start)
        )
    }

    private fun handleList(parts: List<String>, start: Long): PluginResult {
        val count = if (parts.size >= 2) parts[1].trim().toIntOrNull() ?: 10 else 10
        if (overrides.isEmpty()) {
            return PluginResult.success("No user overrides recorded.", elapsed(start))
        }
        val recent = overrides.takeLast(count)
        val sb = buildString {
            append("Recent ${recent.size} overrides:\n")
            recent.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("decision_id")}] ")
                append("AI: '${entry.optString("ai_choice")}' -> User: '${entry.optString("user_choice")}' ")
                append("(${entry.optString("reason")}) ")
                append("[${formatTimestamp(entry.optLong("timestamp"))}]\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleByCategory(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: by_category|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val filtered = overrides.filter { it.optString("category") == category }
        if (filtered.isEmpty()) {
            return PluginResult.success(
                "No overrides in category '$category'.",
                elapsed(start)
            )
        }
        val sb = buildString {
            append("Overrides in '$category' (${filtered.size}):\n")
            filtered.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.optString("decision_id")}] ")
                append("AI: '${entry.optString("ai_choice")}' -> User: '${entry.optString("user_choice")}' ")
                append("(${entry.optString("reason")})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleLearn(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: learn|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val filtered = overrides.filter { it.optString("category") == category }
        if (filtered.isEmpty()) {
            return PluginResult.success(
                "No overrides in category '$category' to learn from.",
                elapsed(start)
            )
        }

        // Analyze patterns: group by AI choice to see what the AI keeps getting wrong
        val aiChoiceCounts = filtered.groupBy { it.optString("ai_choice").lowercase() }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Analyze common reasons
        val reasonCounts = filtered.groupBy { it.optString("reason").lowercase() }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Analyze what users prefer instead
        val userPreferences = filtered.groupBy { it.optString("user_choice").lowercase() }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Find AI choice -> user choice transition patterns
        val transitions = filtered.groupBy {
            "${it.optString("ai_choice")} -> ${it.optString("user_choice")}"
        }.mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        val sb = buildString {
            append("Learning analysis for '$category' (${filtered.size} overrides):\n\n")

            append("AI choices most often overridden:\n")
            aiChoiceCounts.take(5).forEach { (choice, count) ->
                append("  - '$choice': overridden $count time(s)\n")
            }

            append("\nUser preferred alternatives:\n")
            userPreferences.take(5).forEach { (choice, count) ->
                append("  - '$choice': chosen $count time(s)\n")
            }

            append("\nMost common transition patterns:\n")
            transitions.take(5).forEach { (transition, count) ->
                append("  - $transition ($count time(s))\n")
            }

            append("\nCommon override reasons:\n")
            reasonCounts.take(5).forEach { (reason, count) ->
                append("  - '$reason': $count time(s)\n")
            }

            append("\nRecommendation: ")
            if (filtered.size >= 5) {
                val topTransition = transitions.firstOrNull()
                if (topTransition != null && topTransition.value >= 3) {
                    append("Strong pattern detected: '${topTransition.key}' occurs ${topTransition.value} times. ")
                    append("Consider defaulting to the user's preference in this category.")
                } else {
                    append("No single dominant pattern yet. Continue collecting data.")
                }
            } else {
                append("Insufficient data (${filtered.size} overrides). Need at least 5 for reliable patterns.")
            }
        }
        return PluginResult.success(sb, elapsed(start))
    }

    private fun handleStats(start: Long): PluginResult {
        if (overrides.isEmpty()) {
            return PluginResult.success("No user overrides recorded.", elapsed(start))
        }

        val total = overrides.size
        val byCategory = overrides.groupBy { it.optString("category", "general") }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        val earliest = overrides.minOf { it.optLong("timestamp") }
        val latest = overrides.maxOf { it.optLong("timestamp") }
        val spanDays = ((latest - earliest) / 86400000.0).coerceAtLeast(1.0)
        val overridesPerDay = total / spanDays

        // Unique decision IDs
        val uniqueDecisions = overrides.map { it.optString("decision_id") }.toSet().size

        val sb = buildString {
            append("User Override Statistics:\n")
            append("  Total overrides: $total\n")
            append("  Unique decisions overridden: $uniqueDecisions\n")
            append("  Timespan: ${formatTimestamp(earliest)} to ${formatTimestamp(latest)}\n")
            append("  Override rate: ${"%.1f".format(overridesPerDay)} per day\n")
            append("  Categories (${byCategory.size}):\n")
            byCategory.forEach { (category, count) ->
                val pct = (count * 100) / total
                append("    $category: $count ($pct%)\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleClear(start: Long): PluginResult {
        val count = overrides.size
        overrides.clear()
        save()
        return PluginResult.success("Cleared $count override records.", elapsed(start))
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts == 0L) return "unknown"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ts))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun save() {
        val arr = JSONArray()
        overrides.forEach { arr.put(it) }
        prefs.edit().putString(KEY_OVERRIDES, arr.toString()).apply()
    }

    private fun load() {
        val data = prefs.getString(KEY_OVERRIDES, null) ?: return
        try {
            val arr = JSONArray(data)
            overrides.clear()
            for (i in 0 until arr.length()) {
                overrides.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) { }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    override fun destroy() {
        overrides.clear()
    }
}
