package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Preference crystallization plugin that records observations about user preferences
 * and crystallizes them into stable patterns with confidence scores.
 *
 * Observations are grouped by category. Crystallization analyzes observations
 * within a category and produces a summary preference with a confidence score
 * that increases with more consistent observations.
 *
 * Commands:
 *   observe|category|observation    - Record an observation in a category
 *   crystallize|category            - Analyze observations and produce a summary preference
 *   get|category                    - Get the crystallized preference for a category
 *   list_categories                 - List all categories with observation counts
 *   confidence|category             - Get the confidence score for a category
 *   clear|category                  - Clear all observations in a category
 */
class PreferenceCrystallizationPlugin : Plugin {

    companion object {
        private const val ID = "preference_crystallization"
        private const val PREFS = "preference_crystallization_plugin"
        private const val KEY_OBSERVATIONS = "observations_json"
        private const val KEY_CRYSTALS = "crystals_json"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Preference Crystallization"

    override val description: String =
        "Observation-based preference learner. Commands: observe|category|observation, crystallize|category, get|category, list_categories, confidence|category, clear|category"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "observe" -> observe(parts, start)
                "crystallize" -> crystallize(parts, start)
                "get" -> getCrystal(parts, start)
                "list_categories" -> listCategories(start)
                "confidence" -> getConfidence(parts, start)
                "clear" -> clearCategory(parts, start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: observe|category|observation, crystallize|category, get|category, list_categories, confidence|category, clear|category",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Preference crystallization failed: ${e.message}", elapsed(start))
        }
    }

    private fun observe(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
            return PluginResult.error("Usage: observe|category|observation", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val observation = parts[2].trim()

        val observations = getObservations()
        val categoryArray = if (observations.has(category)) {
            observations.getJSONArray(category)
        } else {
            JSONArray().also { observations.put(category, it) }
        }

        val entry = JSONObject().apply {
            put("text", observation)
            put("timestamp", System.currentTimeMillis())
        }
        categoryArray.put(entry)
        saveObservations(observations)

        return PluginResult.success(
            "Recorded observation in '$category' (${categoryArray.length()} total): $observation",
            elapsed(start)
        )
    }

    private fun crystallize(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: crystallize|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val observations = getObservations()

        if (!observations.has(category)) {
            return PluginResult.error("No observations found for category '$category'", elapsed(start))
        }

        val categoryArray = observations.getJSONArray(category)
        if (categoryArray.length() == 0) {
            return PluginResult.error("No observations found for category '$category'", elapsed(start))
        }

        // Collect all observation texts
        val texts = mutableListOf<String>()
        for (i in 0 until categoryArray.length()) {
            texts.add(categoryArray.getJSONObject(i).getString("text").lowercase())
        }

        // Build word frequency map for keyword extraction
        val wordFreq = mutableMapOf<String, Int>()
        for (text in texts) {
            val words = text.split("\\s+".toRegex())
                .map { it.replace("[^a-z0-9]".toRegex(), "") }
                .filter { it.length > 2 }
            for (word in words) {
                wordFreq[word] = (wordFreq[word] ?: 0) + 1
            }
        }

        // Top keywords appearing in multiple observations indicate consistency
        val sortedWords = wordFreq.entries.sortedByDescending { it.value }
        val topKeywords = sortedWords.take(5).map { it.key }

        // Calculate consistency: ratio of keywords appearing in multiple observations
        val multiObsKeywords = sortedWords.count { it.value > 1 }
        val totalKeywords = sortedWords.size.coerceAtLeast(1)
        val consistencyRatio = multiObsKeywords.toDouble() / totalKeywords

        // Confidence based on observation count and consistency
        val countFactor = (categoryArray.length().coerceAtMost(20).toDouble() / 20.0) * 50.0
        val consistencyFactor = consistencyRatio * 50.0
        val confidence = (countFactor + consistencyFactor).coerceIn(0.0, 100.0)

        // Build summary from most recent observation + top keywords
        val mostRecent = categoryArray.getJSONObject(categoryArray.length() - 1).getString("text")
        val summary = if (topKeywords.isNotEmpty()) {
            "Based on ${categoryArray.length()} observations. Key themes: ${topKeywords.joinToString(", ")}. Latest: $mostRecent"
        } else {
            "Based on ${categoryArray.length()} observation(s). Latest: $mostRecent"
        }

        // Store crystal
        val crystals = getCrystals()
        val crystal = JSONObject().apply {
            put("category", category)
            put("summary", summary)
            put("confidence", confidence)
            put("observationCount", categoryArray.length())
            put("topKeywords", JSONArray(topKeywords))
            put("crystallizedAt", System.currentTimeMillis())
        }
        crystals.put(category, crystal)
        saveCrystals(crystals)

        return PluginResult.success(
            "Crystallized preference for '$category':\n" +
                    "  Summary: $summary\n" +
                    "  Confidence: ${"%.1f".format(confidence)}%\n" +
                    "  Based on ${categoryArray.length()} observation(s)",
            elapsed(start)
        )
    }

    private fun getCrystal(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: get|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val crystals = getCrystals()

        if (!crystals.has(category)) {
            return PluginResult.error(
                "No crystallized preference for '$category'. Use crystallize|$category first.",
                elapsed(start)
            )
        }

        val crystal = crystals.getJSONObject(category)
        return PluginResult.success(
            "Preference for '$category':\n" +
                    "  Summary: ${crystal.getString("summary")}\n" +
                    "  Confidence: ${"%.1f".format(crystal.getDouble("confidence"))}%\n" +
                    "  Observations: ${crystal.getInt("observationCount")}",
            elapsed(start)
        )
    }

    private fun listCategories(start: Long): PluginResult {
        val observations = getObservations()
        if (observations.length() == 0) {
            return PluginResult.success("No categories recorded.", elapsed(start))
        }

        val crystals = getCrystals()
        val sb = StringBuilder("Categories:\n")
        val keys = observations.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            val count = observations.getJSONArray(category).length()
            val crystallized = if (crystals.has(category)) " [crystallized]" else ""
            sb.append("  - $category: $count observation(s)$crystallized\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun getConfidence(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: confidence|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val crystals = getCrystals()

        if (!crystals.has(category)) {
            return PluginResult.error(
                "No crystallized preference for '$category'. Use crystallize|$category first.",
                elapsed(start)
            )
        }

        val crystal = crystals.getJSONObject(category)
        val confidence = crystal.getDouble("confidence")
        val level = when {
            confidence >= 80.0 -> "high"
            confidence >= 50.0 -> "moderate"
            confidence >= 25.0 -> "low"
            else -> "very low"
        }

        return PluginResult.success(
            "Confidence for '$category': ${"%.1f".format(confidence)}% ($level)\n" +
                    "Based on ${crystal.getInt("observationCount")} observation(s)",
            elapsed(start)
        )
    }

    private fun clearCategory(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: clear|category", elapsed(start))
        }
        val category = parts[1].trim().lowercase()
        val observations = getObservations()
        val crystals = getCrystals()

        val hadObservations = observations.has(category)
        val hadCrystal = crystals.has(category)

        if (!hadObservations && !hadCrystal) {
            return PluginResult.error("Category '$category' not found.", elapsed(start))
        }

        if (hadObservations) observations.remove(category)
        if (hadCrystal) crystals.remove(category)

        saveObservations(observations)
        saveCrystals(crystals)

        return PluginResult.success("Cleared all data for category '$category'", elapsed(start))
    }

    private fun getObservations(): JSONObject {
        val raw = preferences.getString(KEY_OBSERVATIONS, "{}")
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveObservations(observations: JSONObject) {
        preferences.edit().putString(KEY_OBSERVATIONS, observations.toString()).apply()
    }

    private fun getCrystals(): JSONObject {
        val raw = preferences.getString(KEY_CRYSTALS, "{}")
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveCrystals(crystals: JSONObject) {
        preferences.edit().putString(KEY_CRYSTALS, crystals.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
