package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 6 Self-Improvement: Error Pattern Learning Plugin.
 *
 * Tracks recurring errors across plugins and learns patterns to help avoid them.
 * Detects duplicate errors, tracks frequency, and generates avoidance suggestions
 * based on accumulated error history.
 *
 * Commands:
 *   record|<plugin_id>|<error_msg>  - Record an error occurrence for a plugin
 *   patterns                         - List all known error patterns across plugins
 *   suggest|<plugin_id>             - Get top error patterns and avoidance suggestions for a plugin
 *   clear                            - Clear all recorded error data
 *   stats                            - Show summary statistics of recorded errors
 */
class ErrorPatternLearningPlugin : Plugin {

    companion object {
        private const val ID = "error_pattern_learning"
        private const val TAG = "ErrorPatternLearning"
        private const val PREFS_NAME = "tronprotocol_error_patterns"
        private const val KEY_ERRORS = "error_records"
    }

    private var prefs: SharedPreferences? = null

    // In-memory map: pluginId -> list of error entries (each entry has message + count + timestamps)
    private val errorMap = mutableMapOf<String, MutableList<ErrorEntry>>()

    override val id: String = ID

    override val name: String = "Error Pattern Learning"

    override val description: String =
        "Track and learn from recurring errors. Commands: record|plugin_id|error_msg, " +
            "patterns, suggest|plugin_id, clear, stats"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "record" -> handleRecord(parts, start)
                "patterns" -> handlePatterns(start)
                "suggest" -> handleSuggest(parts, start)
                "clear" -> handleClear(start)
                "stats" -> handleStats(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: record|plugin_id|error_msg, patterns, suggest|plugin_id, clear, stats",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ErrorPatternLearningPlugin", e)
            PluginResult.error("Error pattern learning failed: ${e.message}", elapsed(start))
        }
    }

    private fun handleRecord(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
            return PluginResult.error("Usage: record|plugin_id|error_msg", elapsed(start))
        }
        val pluginId = parts[1].trim()
        val errorMsg = parts[2].trim()

        val entries = errorMap.getOrPut(pluginId) { mutableListOf() }
        val existing = entries.find { it.message.equals(errorMsg, ignoreCase = true) }

        if (existing != null) {
            existing.count++
            existing.lastSeen = System.currentTimeMillis()
        } else {
            entries.add(ErrorEntry(
                message = errorMsg,
                count = 1,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            ))
        }

        saveErrors()

        val totalForPlugin = entries.sumOf { it.count }
        return PluginResult.success(
            "Recorded error for '$pluginId': \"$errorMsg\" (occurrence #${existing?.count ?: 1}, total errors for plugin: $totalForPlugin)",
            elapsed(start)
        )
    }

    private fun handlePatterns(start: Long): PluginResult {
        if (errorMap.isEmpty()) {
            return PluginResult.success("No error patterns recorded yet.", elapsed(start))
        }

        val sb = buildString {
            append("Error Patterns (${errorMap.size} plugin(s)):\n")
            for ((pluginId, entries) in errorMap) {
                val sorted = entries.sortedByDescending { it.count }
                append("\n[$pluginId] ${entries.size} unique error(s), ${entries.sumOf { it.count }} total occurrence(s):\n")
                sorted.forEachIndexed { index, entry ->
                    append("  ${index + 1}. [x${entry.count}] ${entry.message}\n")
                }
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleSuggest(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: suggest|plugin_id", elapsed(start))
        }
        val pluginId = parts[1].trim()
        val entries = errorMap[pluginId]

        if (entries == null || entries.isEmpty()) {
            return PluginResult.success(
                "No error patterns recorded for '$pluginId'. Plugin is operating cleanly.",
                elapsed(start)
            )
        }

        val sorted = entries.sortedByDescending { it.count }
        val topPatterns = sorted.take(5)

        val sb = buildString {
            append("Top error patterns for '$pluginId' (${entries.sumOf { it.count }} total errors):\n\n")
            topPatterns.forEachIndexed { index, entry ->
                append("${index + 1}. [x${entry.count}] ${entry.message}\n")
                append("   Suggestion: ${generateSuggestion(entry)}\n\n")
            }
            if (sorted.size > 5) {
                append("... and ${sorted.size - 5} more unique error pattern(s).\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleClear(start: Long): PluginResult {
        val totalPlugins = errorMap.size
        val totalErrors = errorMap.values.sumOf { entries -> entries.sumOf { it.count } }
        errorMap.clear()
        saveErrors()
        return PluginResult.success(
            "Cleared $totalErrors error record(s) across $totalPlugins plugin(s).",
            elapsed(start)
        )
    }

    private fun handleStats(start: Long): PluginResult {
        if (errorMap.isEmpty()) {
            return PluginResult.success("No error data recorded yet.", elapsed(start))
        }

        val totalPlugins = errorMap.size
        val totalUnique = errorMap.values.sumOf { it.size }
        val totalOccurrences = errorMap.values.sumOf { entries -> entries.sumOf { it.count } }
        val mostErrorProne = errorMap.maxByOrNull { it.value.sumOf { e -> e.count } }
        val mostFrequentEntry = errorMap.values.flatten().maxByOrNull { it.count }

        val sb = buildString {
            append("Error Pattern Statistics:\n")
            append("  Plugins tracked: $totalPlugins\n")
            append("  Unique error patterns: $totalUnique\n")
            append("  Total error occurrences: $totalOccurrences\n")
            if (mostErrorProne != null) {
                append("  Most error-prone plugin: '${mostErrorProne.key}' (${mostErrorProne.value.sumOf { it.count }} occurrences)\n")
            }
            if (mostFrequentEntry != null) {
                append("  Most frequent error: \"${mostFrequentEntry.message}\" (x${mostFrequentEntry.count})\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    /**
     * Generate an avoidance suggestion based on error frequency and content.
     */
    private fun generateSuggestion(entry: ErrorEntry): String {
        val msg = entry.message.lowercase()
        return when {
            entry.count >= 10 -> "Critical recurring error (${entry.count}x). Investigate root cause immediately and consider disabling the triggering feature."
            entry.count >= 5 -> "Frequent error (${entry.count}x). Add input validation or pre-condition checks to prevent this failure."
            msg.contains("timeout") || msg.contains("timed out") ->
                "Timeout-related. Consider increasing timeout thresholds or adding retry logic with exponential backoff."
            msg.contains("null") || msg.contains("npe") ->
                "Null-related. Add null-safety checks and validate inputs before processing."
            msg.contains("permission") || msg.contains("denied") ->
                "Permission-related. Verify required permissions are granted before executing the operation."
            msg.contains("network") || msg.contains("connection") || msg.contains("socket") ->
                "Network-related. Add connectivity checks and implement offline fallback behavior."
            msg.contains("parse") || msg.contains("format") || msg.contains("invalid") ->
                "Parsing/format error. Validate input format before processing and add robust error handling."
            msg.contains("memory") || msg.contains("oom") ->
                "Memory-related. Review resource usage and add memory pressure handling."
            entry.count >= 3 -> "Recurring error (${entry.count}x). Add specific error handling for this case."
            else -> "Monitor this error. If it recurs, add targeted error handling."
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadErrors()
        Log.d(TAG, "ErrorPatternLearningPlugin initialized with ${errorMap.size} tracked plugin(s)")
    }

    override fun destroy() {
        errorMap.clear()
        prefs = null
    }

    // ---- Persistence ----

    private fun saveErrors() {
        val root = JSONObject()
        for ((pluginId, entries) in errorMap) {
            val arr = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                obj.put("message", entry.message)
                obj.put("count", entry.count)
                obj.put("firstSeen", entry.firstSeen)
                obj.put("lastSeen", entry.lastSeen)
                arr.put(obj)
            }
            root.put(pluginId, arr)
        }
        prefs?.edit()?.putString(KEY_ERRORS, root.toString())?.apply()
    }

    private fun loadErrors() {
        val data = prefs?.getString(KEY_ERRORS, null) ?: return
        try {
            val root = JSONObject(data)
            errorMap.clear()
            for (pluginId in root.keys()) {
                val arr = root.getJSONArray(pluginId)
                val entries = mutableListOf<ErrorEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    entries.add(ErrorEntry(
                        message = obj.getString("message"),
                        count = obj.getInt("count"),
                        firstSeen = obj.getLong("firstSeen"),
                        lastSeen = obj.getLong("lastSeen")
                    ))
                }
                errorMap[pluginId] = entries
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading error patterns", e)
        }
    }

    // ---- Data class ----

    private data class ErrorEntry(
        val message: String,
        var count: Int,
        val firstSeen: Long,
        var lastSeen: Long
    )
}
