package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class UserFeedbackPlugin : Plugin {
    override val id: String = "user_feedback"
    override val name: String = "User Feedback"
    override val description: String = "Collects and analyzes user feedback to improve system behavior"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("user_feedback_plugin", Context.MODE_PRIVATE)
        initialized = true
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        if (!initialized) {
            return PluginResult.error("Plugin not initialized", System.currentTimeMillis() - startTime)
        }

        val parts = input.split("|").map { it.trim() }
        if (parts.isEmpty()) {
            return PluginResult.error("No command provided. Available: thumbs_up, thumbs_down, rate, history, stats", System.currentTimeMillis() - startTime)
        }

        return try {
            when (parts[0].lowercase()) {
                "thumbs_up" -> handleThumbsUp(parts, startTime)
                "thumbs_down" -> handleThumbsDown(parts, startTime)
                "rate" -> handleRate(parts, startTime)
                "history" -> handleHistory(parts, startTime)
                "stats" -> handleStats(startTime)
                else -> PluginResult.error("Unknown command: ${parts[0]}. Available: thumbs_up, thumbs_down, rate, history, stats", System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            PluginResult.error("Error executing command: ${e.message}", System.currentTimeMillis() - startTime)
        }
    }

    private fun handleThumbsUp(parts: List<String>, startTime: Long): PluginResult {
        val context = if (parts.size > 1) parts[1] else "general"
        val feedback = JSONObject().apply {
            put("type", "thumbs_up")
            put("context", context)
            put("timestamp", System.currentTimeMillis())
            put("rating", 5)
        }
        storeFeedback(feedback)
        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Positive feedback recorded for context: $context", elapsed)
    }

    private fun handleThumbsDown(parts: List<String>, startTime: Long): PluginResult {
        val context = if (parts.size > 1) parts[1] else "general"
        val reason = if (parts.size > 2) parts[2] else "not specified"
        val feedback = JSONObject().apply {
            put("type", "thumbs_down")
            put("context", context)
            put("reason", reason)
            put("timestamp", System.currentTimeMillis())
            put("rating", 1)
        }
        storeFeedback(feedback)
        storeReason(reason)
        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Negative feedback recorded for context: $context, reason: $reason", elapsed)
    }

    private fun handleRate(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: rate|context|1-5", System.currentTimeMillis() - startTime)
        }
        val context = parts[1]
        val rating = parts[2].toIntOrNull()
        if (rating == null || rating < 1 || rating > 5) {
            return PluginResult.error("Rating must be an integer between 1 and 5", System.currentTimeMillis() - startTime)
        }
        val type = when {
            rating >= 4 -> "thumbs_up"
            rating <= 2 -> "thumbs_down"
            else -> "neutral"
        }
        val feedback = JSONObject().apply {
            put("type", type)
            put("context", context)
            put("rating", rating)
            put("timestamp", System.currentTimeMillis())
        }
        storeFeedback(feedback)
        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Rating $rating recorded for context: $context", elapsed)
    }

    private fun handleHistory(parts: List<String>, startTime: Long): PluginResult {
        val count = if (parts.size > 1) parts[1].toIntOrNull() ?: 10 else 10
        val feedbackArray = loadFeedbackArray()
        val total = feedbackArray.length()
        val startIndex = maxOf(0, total - count)

        val result = JSONObject()
        result.put("total_feedback", total)
        result.put("showing", minOf(count, total))

        val historyArray = JSONArray()
        for (i in startIndex until total) {
            historyArray.put(feedbackArray.getJSONObject(i))
        }
        result.put("history", historyArray)

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(result.toString(2), elapsed)
    }

    private fun handleStats(startTime: Long): PluginResult {
        val feedbackArray = loadFeedbackArray()
        val total = feedbackArray.length()

        var positive = 0
        var negative = 0
        var neutral = 0
        var ratingSum = 0
        var ratingCount = 0
        val contextCounts = mutableMapOf<String, Int>()

        for (i in 0 until total) {
            val item = feedbackArray.getJSONObject(i)
            when (item.optString("type")) {
                "thumbs_up" -> positive++
                "thumbs_down" -> negative++
                "neutral" -> neutral++
            }
            val rating = item.optInt("rating", 0)
            if (rating > 0) {
                ratingSum += rating
                ratingCount++
            }
            val ctx = item.optString("context", "general")
            contextCounts[ctx] = (contextCounts[ctx] ?: 0) + 1
        }

        val reasons = loadReasons()
        val reasonCounts = mutableMapOf<String, Int>()
        for (i in 0 until reasons.length()) {
            val reason = reasons.getString(i)
            reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
        }
        val topReasons = reasonCounts.entries.sortedByDescending { it.value }.take(5)

        val avgRating = if (ratingCount > 0) ratingSum.toDouble() / ratingCount else 0.0
        val positiveRatio = if (total > 0) positive.toDouble() / total else 0.0

        val stats = JSONObject().apply {
            put("total_feedback", total)
            put("positive", positive)
            put("negative", negative)
            put("neutral", neutral)
            put("positive_ratio", String.format("%.2f", positiveRatio))
            put("average_rating", String.format("%.2f", avgRating))
            put("top_contexts", JSONObject().also { obj ->
                contextCounts.entries.sortedByDescending { it.value }.take(5).forEach { (k, v) ->
                    obj.put(k, v)
                }
            })
            put("common_negative_reasons", JSONArray().also { arr ->
                topReasons.forEach { (reason, count) ->
                    arr.put(JSONObject().apply {
                        put("reason", reason)
                        put("count", count)
                    })
                }
            })
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(stats.toString(2), elapsed)
    }

    private fun storeFeedback(feedback: JSONObject) {
        val array = loadFeedbackArray()
        array.put(feedback)
        // Keep at most 1000 entries
        val trimmed = if (array.length() > 1000) {
            val newArray = JSONArray()
            for (i in array.length() - 1000 until array.length()) {
                newArray.put(array.getJSONObject(i))
            }
            newArray
        } else {
            array
        }
        prefs.edit().putString("feedback_list", trimmed.toString()).apply()
    }

    private fun loadFeedbackArray(): JSONArray {
        val raw = prefs.getString("feedback_list", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun storeReason(reason: String) {
        val reasons = loadReasons()
        reasons.put(reason)
        // Keep at most 500 reasons
        val trimmed = if (reasons.length() > 500) {
            val newArray = JSONArray()
            for (i in reasons.length() - 500 until reasons.length()) {
                newArray.put(reasons.getString(i))
            }
            newArray
        } else {
            reasons
        }
        prefs.edit().putString("negative_reasons", trimmed.toString()).apply()
    }

    private fun loadReasons(): JSONArray {
        val raw = prefs.getString("negative_reasons", null) ?: return JSONArray()
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
