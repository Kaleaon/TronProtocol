package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CapabilityDiscoveryPlugin : Plugin {
    override val id: String = "capability_discovery"
    override val name: String = "Capability Discovery"
    override val description: String = "Logs capability gaps, suggests solutions, and tracks resolution progress"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("capability_discovery_plugin", Context.MODE_PRIVATE)
        initialized = true
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        if (!initialized) {
            return PluginResult.error("Plugin not initialized", System.currentTimeMillis() - startTime)
        }

        val parts = input.split("|").map { it.trim() }
        if (parts.isEmpty()) {
            return PluginResult.error("No command provided. Available: log_gap, list_gaps, suggest, resolve, stats", System.currentTimeMillis() - startTime)
        }

        return try {
            when (parts[0].lowercase()) {
                "log_gap" -> handleLogGap(parts, startTime)
                "list_gaps" -> handleListGaps(startTime)
                "suggest" -> handleSuggest(parts, startTime)
                "resolve" -> handleResolve(parts, startTime)
                "stats" -> handleStats(startTime)
                else -> PluginResult.error("Unknown command: ${parts[0]}. Available: log_gap, list_gaps, suggest, resolve, stats", System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            PluginResult.error("Error executing command: ${e.message}", System.currentTimeMillis() - startTime)
        }
    }

    private fun handleLogGap(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: log_gap|description", System.currentTimeMillis() - startTime)
        }
        val description = parts.subList(1, parts.size).joinToString("|")
        val gapId = UUID.randomUUID().toString().take(8)

        val gaps = loadGaps()
        val gap = JSONObject().apply {
            put("id", gapId)
            put("description", description)
            put("status", "open")
            put("created_at", System.currentTimeMillis())
            put("occurrences", 1)
            put("suggestions", JSONArray())
            put("solution", "")
        }

        // Check for similar existing gaps
        var existingIndex = -1
        for (i in 0 until gaps.length()) {
            val existing = gaps.getJSONObject(i)
            if (existing.getString("description").equals(description, ignoreCase = true) &&
                existing.getString("status") == "open"
            ) {
                existingIndex = i
                break
            }
        }

        if (existingIndex >= 0) {
            val existing = gaps.getJSONObject(existingIndex)
            val occurrences = existing.optInt("occurrences", 1) + 1
            existing.put("occurrences", occurrences)
            existing.put("last_seen", System.currentTimeMillis())
            gaps.put(existingIndex, existing)
            prefs.edit().putString("gaps", gaps.toString()).apply()
            val elapsed = System.currentTimeMillis() - startTime
            return PluginResult.success(
                "Existing gap updated (occurrences: $occurrences): id=${existing.getString("id")}, description=$description",
                elapsed
            )
        }

        gaps.put(gap)
        prefs.edit().putString("gaps", gaps.toString()).apply()

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Capability gap logged: id=$gapId, description=$description", elapsed)
    }

    private fun handleListGaps(startTime: Long): PluginResult {
        val gaps = loadGaps()
        val listing = JSONObject().apply {
            put("total_gaps", gaps.length())

            val openGaps = JSONArray()
            val resolvedGaps = JSONArray()

            for (i in 0 until gaps.length()) {
                val gap = gaps.getJSONObject(i)
                val summary = JSONObject().apply {
                    put("id", gap.getString("id"))
                    put("description", gap.getString("description"))
                    put("status", gap.getString("status"))
                    put("occurrences", gap.optInt("occurrences", 1))
                    put("created_at", gap.getLong("created_at"))
                }
                if (gap.getString("status") == "resolved") {
                    summary.put("solution", gap.optString("solution", ""))
                    resolvedGaps.put(summary)
                } else {
                    openGaps.put(summary)
                }
            }

            put("open", openGaps)
            put("resolved", resolvedGaps)
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(listing.toString(2), elapsed)
    }

    private fun handleSuggest(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: suggest|gap_id", System.currentTimeMillis() - startTime)
        }
        val gapId = parts[1]
        val gaps = loadGaps()

        var targetGap: JSONObject? = null
        var targetIndex = -1
        for (i in 0 until gaps.length()) {
            val gap = gaps.getJSONObject(i)
            if (gap.getString("id") == gapId) {
                targetGap = gap
                targetIndex = i
                break
            }
        }

        if (targetGap == null) {
            return PluginResult.error("Gap not found: $gapId", System.currentTimeMillis() - startTime)
        }

        val description = targetGap.getString("description")
        val occurrences = targetGap.optInt("occurrences", 1)
        val suggestions = generateSuggestions(description, occurrences)

        val existingSuggestions = targetGap.optJSONArray("suggestions") ?: JSONArray()
        for (i in 0 until suggestions.length()) {
            existingSuggestions.put(suggestions.getString(i))
        }
        targetGap.put("suggestions", existingSuggestions)
        gaps.put(targetIndex, targetGap)
        prefs.edit().putString("gaps", gaps.toString()).apply()

        val result = JSONObject().apply {
            put("gap_id", gapId)
            put("description", description)
            put("occurrences", occurrences)
            put("new_suggestions", suggestions)
            put("all_suggestions", existingSuggestions)
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(result.toString(2), elapsed)
    }

    private fun handleResolve(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: resolve|gap_id|solution", System.currentTimeMillis() - startTime)
        }
        val gapId = parts[1]
        val solution = parts.subList(2, parts.size).joinToString("|")
        val gaps = loadGaps()

        var targetIndex = -1
        for (i in 0 until gaps.length()) {
            if (gaps.getJSONObject(i).getString("id") == gapId) {
                targetIndex = i
                break
            }
        }

        if (targetIndex < 0) {
            return PluginResult.error("Gap not found: $gapId", System.currentTimeMillis() - startTime)
        }

        val gap = gaps.getJSONObject(targetIndex)
        if (gap.getString("status") == "resolved") {
            return PluginResult.error("Gap is already resolved: $gapId", System.currentTimeMillis() - startTime)
        }

        gap.put("status", "resolved")
        gap.put("solution", solution)
        gap.put("resolved_at", System.currentTimeMillis())
        gaps.put(targetIndex, gap)
        prefs.edit().putString("gaps", gaps.toString()).apply()

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Gap resolved: id=$gapId, solution=$solution", elapsed)
    }

    private fun handleStats(startTime: Long): PluginResult {
        val gaps = loadGaps()
        var openCount = 0
        var resolvedCount = 0
        var totalOccurrences = 0
        var highFrequencyGaps = 0

        for (i in 0 until gaps.length()) {
            val gap = gaps.getJSONObject(i)
            val occurrences = gap.optInt("occurrences", 1)
            totalOccurrences += occurrences
            if (gap.getString("status") == "resolved") {
                resolvedCount++
            } else {
                openCount++
                if (occurrences >= 3) highFrequencyGaps++
            }
        }

        val resolutionRate = if (gaps.length() > 0) {
            String.format("%.1f%%", resolvedCount.toDouble() / gaps.length() * 100)
        } else {
            "N/A"
        }

        // Find most frequent open gaps
        val openGapsList = mutableListOf<Pair<String, Int>>()
        for (i in 0 until gaps.length()) {
            val gap = gaps.getJSONObject(i)
            if (gap.getString("status") == "open") {
                openGapsList.add(gap.getString("description") to gap.optInt("occurrences", 1))
            }
        }
        val topGaps = openGapsList.sortedByDescending { it.second }.take(5)

        val stats = JSONObject().apply {
            put("total_gaps", gaps.length())
            put("open", openCount)
            put("resolved", resolvedCount)
            put("resolution_rate", resolutionRate)
            put("total_occurrences", totalOccurrences)
            put("high_frequency_open_gaps", highFrequencyGaps)
            put("most_frequent_open", JSONArray().also { arr ->
                topGaps.forEach { (desc, count) ->
                    arr.put(JSONObject().apply {
                        put("description", desc)
                        put("occurrences", count)
                    })
                }
            })
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(stats.toString(2), elapsed)
    }

    private fun generateSuggestions(description: String, occurrences: Int): JSONArray {
        val suggestions = JSONArray()
        val descLower = description.lowercase()

        // Context-aware suggestion generation based on keywords
        if (descLower.contains("api") || descLower.contains("network") || descLower.contains("connect")) {
            suggestions.put("Add retry logic with exponential backoff for network calls")
            suggestions.put("Implement a caching layer to reduce API dependency")
        }
        if (descLower.contains("slow") || descLower.contains("performance") || descLower.contains("latency")) {
            suggestions.put("Profile the operation and identify bottlenecks")
            suggestions.put("Consider moving heavy processing to a background thread")
        }
        if (descLower.contains("error") || descLower.contains("crash") || descLower.contains("fail")) {
            suggestions.put("Add comprehensive error handling and fallback behavior")
            suggestions.put("Implement circuit breaker pattern for the failing component")
        }
        if (descLower.contains("missing") || descLower.contains("lack") || descLower.contains("need")) {
            suggestions.put("Create a new plugin to address this capability gap")
            suggestions.put("Extend an existing plugin with the needed functionality")
        }
        if (descLower.contains("data") || descLower.contains("storage") || descLower.contains("memory")) {
            suggestions.put("Implement a persistent storage layer for the data")
            suggestions.put("Add data compression or cleanup routines")
        }

        if (occurrences >= 5) {
            suggestions.put("HIGH PRIORITY: This gap has occurred $occurrences times. Consider immediate resolution.")
        } else if (occurrences >= 3) {
            suggestions.put("MEDIUM PRIORITY: Recurring gap ($occurrences occurrences). Should be addressed soon.")
        }

        // Default suggestion if no keywords matched
        if (suggestions.length() == 0) {
            suggestions.put("Analyze the gap further to determine root cause")
            suggestions.put("Consider creating a dedicated plugin or extending existing functionality")
        }

        return suggestions
    }

    private fun loadGaps(): JSONArray {
        val raw = prefs.getString("gaps", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    override fun destroy() {
        initialized = false
    }
}
