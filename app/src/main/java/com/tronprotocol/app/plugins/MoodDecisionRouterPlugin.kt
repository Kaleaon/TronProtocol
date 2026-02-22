package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mood-based decision router that routes decisions differently based on
 * the current operational mood/state.
 *
 * Supports five moods: focused, cautious, exploratory, efficient, creative.
 * Policies map mood + action type combinations to recommended behaviors.
 * The "route" command matches decision descriptions against policies for
 * the current mood and returns recommended behavior.
 *
 * Commands:
 *   set_mood|mood_name                    - Set current mood
 *   get_mood                              - Get current mood
 *   route|decision_description            - Route a decision based on current mood
 *   add_policy|mood|action_type|behavior  - Add a routing policy
 *   list_policies                         - List all policies
 *   history                               - Show mood change history
 *   clear_policies                        - Remove all policies
 */
class MoodDecisionRouterPlugin : Plugin {

    companion object {
        private const val ID = "mood_decision_router"
        private const val PREFS = "mood_decision_router_plugin"
        private const val KEY_MOOD = "current_mood"
        private const val KEY_POLICIES = "policies_json"
        private const val KEY_HISTORY = "mood_history_json"

        private val VALID_MOODS = setOf("focused", "cautious", "exploratory", "efficient", "creative")

        private val DEFAULT_BEHAVIORS = mapOf(
            "focused" to "Prioritize the most critical aspect and handle it directly with minimal distraction.",
            "cautious" to "Proceed carefully, validate assumptions, and prefer safe options.",
            "exploratory" to "Consider unconventional approaches and gather more information before deciding.",
            "efficient" to "Choose the fastest path to completion with minimal overhead.",
            "creative" to "Think broadly, combine ideas, and propose novel solutions."
        )
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Mood Decision Router"

    override val description: String =
        "Mood-based decision routing. Commands: set_mood|mood, get_mood, route|decision, add_policy|mood|action_type|behavior, list_policies, history, clear_policies"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set_mood" -> setMood(parts, start)
                "get_mood" -> getMood(start)
                "route" -> routeDecision(parts, start)
                "add_policy" -> addPolicy(parts, start)
                "list_policies" -> listPolicies(start)
                "history" -> showHistory(start)
                "clear_policies" -> clearPolicies(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: set_mood|mood, get_mood, route|decision, add_policy|mood|action_type|behavior, list_policies, history, clear_policies",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Mood decision router failed: ${e.message}", elapsed(start))
        }
    }

    private fun setMood(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error(
                "Usage: set_mood|mood_name. Valid moods: ${VALID_MOODS.joinToString(", ")}",
                elapsed(start)
            )
        }
        val mood = parts[1].trim().lowercase()

        if (mood !in VALID_MOODS) {
            return PluginResult.error(
                "Invalid mood '$mood'. Valid moods: ${VALID_MOODS.joinToString(", ")}",
                elapsed(start)
            )
        }

        val previousMood = preferences.getString(KEY_MOOD, "focused") ?: "focused"
        preferences.edit().putString(KEY_MOOD, mood).apply()

        // Record history
        val history = getHistory()
        val entry = JSONObject().apply {
            put("from", previousMood)
            put("to", mood)
            put("timestamp", System.currentTimeMillis())
        }
        history.put(entry)
        saveHistory(history)

        return PluginResult.success("Mood changed from '$previousMood' to '$mood'", elapsed(start))
    }

    private fun getMood(start: Long): PluginResult {
        val mood = preferences.getString(KEY_MOOD, "focused") ?: "focused"
        val defaultBehavior = DEFAULT_BEHAVIORS[mood] ?: ""
        return PluginResult.success(
            "Current mood: $mood\nDefault behavior: $defaultBehavior",
            elapsed(start)
        )
    }

    private fun routeDecision(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: route|decision_description", elapsed(start))
        }
        val decision = parts[1].trim()
        val decisionWords = decision.lowercase().split("\\s+".toRegex()).toSet()
        val currentMood = preferences.getString(KEY_MOOD, "focused") ?: "focused"

        val policies = getPolicies()
        val matchedPolicies = mutableListOf<JSONObject>()

        // Find policies matching current mood with keyword overlap in action_type
        for (i in 0 until policies.length()) {
            val policy = policies.getJSONObject(i)
            if (policy.getString("mood") == currentMood) {
                val actionTypeWords = policy.getString("action_type").lowercase().split("\\s+".toRegex()).toSet()
                val overlap = decisionWords.intersect(actionTypeWords)
                if (overlap.isNotEmpty()) {
                    policy.put("_matchScore", overlap.size)
                    matchedPolicies.add(policy)
                }
            }
        }

        val sb = StringBuilder("Decision routing for: '$decision'\n")
        sb.append("Current mood: $currentMood\n\n")

        if (matchedPolicies.isNotEmpty()) {
            matchedPolicies.sortByDescending { it.getInt("_matchScore") }
            sb.append("Matched policies:\n")
            for (policy in matchedPolicies) {
                sb.append("  - [${policy.getString("action_type")}]: ${policy.getString("behavior")}\n")
            }
            sb.append("\nRecommended behavior: ${matchedPolicies[0].getString("behavior")}")
        } else {
            val defaultBehavior = DEFAULT_BEHAVIORS[currentMood] ?: "Proceed with default behavior."
            sb.append("No specific policies matched.\n")
            sb.append("Default $currentMood behavior: $defaultBehavior")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun addPolicy(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty() || parts[3].trim().isEmpty()) {
            return PluginResult.error("Usage: add_policy|mood|action_type|behavior", elapsed(start))
        }
        val mood = parts[1].trim().lowercase()
        val actionType = parts[2].trim()
        val behavior = parts[3].trim()

        if (mood !in VALID_MOODS) {
            return PluginResult.error(
                "Invalid mood '$mood'. Valid moods: ${VALID_MOODS.joinToString(", ")}",
                elapsed(start)
            )
        }

        val policies = getPolicies()
        val policy = JSONObject().apply {
            put("mood", mood)
            put("action_type", actionType)
            put("behavior", behavior)
            put("created", System.currentTimeMillis())
        }
        policies.put(policy)
        savePolicies(policies)

        return PluginResult.success(
            "Added policy: when $mood and action matches '$actionType' -> '$behavior' (${policies.length()} total policies)",
            elapsed(start)
        )
    }

    private fun listPolicies(start: Long): PluginResult {
        val policies = getPolicies()
        if (policies.length() == 0) {
            return PluginResult.success("No policies defined.", elapsed(start))
        }

        val sb = StringBuilder("Policies (${policies.length()}):\n")
        for (mood in VALID_MOODS) {
            val moodPolicies = mutableListOf<JSONObject>()
            for (i in 0 until policies.length()) {
                val p = policies.getJSONObject(i)
                if (p.getString("mood") == mood) {
                    moodPolicies.add(p)
                }
            }
            if (moodPolicies.isNotEmpty()) {
                sb.append("  [$mood]:\n")
                for (p in moodPolicies) {
                    sb.append("    - ${p.getString("action_type")} -> ${p.getString("behavior")}\n")
                }
            }
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun showHistory(start: Long): PluginResult {
        val history = getHistory()
        if (history.length() == 0) {
            return PluginResult.success("No mood changes recorded.", elapsed(start))
        }

        val displayCount = history.length().coerceAtMost(20)
        val startIdx = history.length() - displayCount
        val sb = StringBuilder("Mood history (last $displayCount):\n")
        for (i in startIdx until history.length()) {
            val entry = history.getJSONObject(i)
            sb.append("  ${i + 1}. ${entry.getString("from")} -> ${entry.getString("to")}\n")
        }

        val currentMood = preferences.getString(KEY_MOOD, "focused") ?: "focused"
        sb.append("Current mood: $currentMood")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun clearPolicies(start: Long): PluginResult {
        val policies = getPolicies()
        val count = policies.length()
        savePolicies(JSONArray())
        return PluginResult.success("Cleared $count policy(ies).", elapsed(start))
    }

    private fun getPolicies(): JSONArray {
        val raw = preferences.getString(KEY_POLICIES, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun savePolicies(policies: JSONArray) {
        preferences.edit().putString(KEY_POLICIES, policies.toString()).apply()
    }

    private fun getHistory(): JSONArray {
        val raw = preferences.getString(KEY_HISTORY, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveHistory(history: JSONArray) {
        preferences.edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
