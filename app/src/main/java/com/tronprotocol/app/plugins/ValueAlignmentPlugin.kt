package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Value alignment plugin that tracks core values and checks action alignment.
 *
 * Maintains a set of weighted values (1-10) and evaluates proposed actions
 * against them using keyword matching between action descriptions and value
 * descriptions to produce an alignment score.
 *
 * Commands:
 *   add_value|name|description|weight(1-10)  - Register a core value
 *   list                                      - List all values
 *   check|action_description                  - Check action alignment with values
 *   remove|name                               - Remove a value by name
 *   update_weight|name|new_weight             - Update a value's weight
 *   report                                    - Show all values sorted by weight
 */
class ValueAlignmentPlugin : Plugin {

    companion object {
        private const val ID = "value_alignment"
        private const val PREFS = "value_alignment_plugin"
        private const val KEY_VALUES = "values_json"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Value Alignment"

    override val description: String =
        "Core value tracker and action alignment checker. Commands: add_value|name|description|weight, list, check|action, remove|name, update_weight|name|weight, report"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add_value" -> addValue(parts, start)
                "list" -> listValues(start)
                "check" -> checkAlignment(parts, start)
                "remove" -> removeValue(parts, start)
                "update_weight" -> updateWeight(parts, start)
                "report" -> report(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: add_value|name|description|weight, list, check|action, remove|name, update_weight|name|weight, report",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Value alignment failed: ${e.message}", elapsed(start))
        }
    }

    private fun addValue(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4) {
            return PluginResult.error("Usage: add_value|name|description|weight(1-10)", elapsed(start))
        }
        val valueName = parts[1].trim()
        val desc = parts[2].trim()
        val weight = parts[3].trim().toIntOrNull()
            ?: return PluginResult.error("Weight must be an integer (1-10)", elapsed(start))

        if (weight < 1 || weight > 10) {
            return PluginResult.error("Weight must be between 1 and 10", elapsed(start))
        }

        val values = getValues()

        // Check for duplicate
        for (i in 0 until values.length()) {
            if (values.getJSONObject(i).getString("name").equals(valueName, ignoreCase = true)) {
                return PluginResult.error("Value '$valueName' already exists. Use update_weight to modify.", elapsed(start))
            }
        }

        val value = JSONObject().apply {
            put("name", valueName)
            put("description", desc)
            put("weight", weight)
            put("created", System.currentTimeMillis())
        }
        values.put(value)
        saveValues(values)

        return PluginResult.success(
            "Added value '$valueName' (weight: $weight): $desc",
            elapsed(start)
        )
    }

    private fun listValues(start: Long): PluginResult {
        val values = getValues()
        if (values.length() == 0) {
            return PluginResult.success("No values defined.", elapsed(start))
        }
        val sb = StringBuilder("Values (${values.length()}):\n")
        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            sb.append("  - ${v.getString("name")} [weight: ${v.getInt("weight")}]: ${v.getString("description")}\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun checkAlignment(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: check|action_description", elapsed(start))
        }
        val action = parts[1].trim().lowercase()
        val actionWords = action.split("\\s+".toRegex()).toSet()

        val values = getValues()
        if (values.length() == 0) {
            return PluginResult.success("No values defined. Cannot check alignment.", elapsed(start))
        }

        var totalWeight = 0
        var alignedWeight = 0
        val alignedValues = mutableListOf<String>()

        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            val weight = v.getInt("weight")
            totalWeight += weight

            val descWords = v.getString("description").lowercase().split("\\s+".toRegex()).toSet()
            val overlap = actionWords.intersect(descWords)

            if (overlap.isNotEmpty()) {
                alignedWeight += weight
                alignedValues.add("${v.getString("name")} (matched: ${overlap.joinToString(", ")})")
            }
        }

        val score = if (totalWeight > 0) (alignedWeight * 100) / totalWeight else 0
        val sb = StringBuilder("Alignment check for: '${parts[1].trim()}'\n")
        sb.append("Score: $score% ($alignedWeight/$totalWeight weighted alignment)\n")
        if (alignedValues.isNotEmpty()) {
            sb.append("Aligned values:\n")
            alignedValues.forEach { sb.append("  + $it\n") }
        } else {
            sb.append("No value alignment detected.\n")
        }

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun removeValue(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: remove|name", elapsed(start))
        }
        val valueName = parts[1].trim()
        val values = getValues()
        val newValues = JSONArray()
        var found = false

        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            if (v.getString("name").equals(valueName, ignoreCase = true)) {
                found = true
            } else {
                newValues.put(v)
            }
        }

        if (!found) {
            return PluginResult.error("Value not found: $valueName", elapsed(start))
        }

        saveValues(newValues)
        return PluginResult.success("Removed value '$valueName'", elapsed(start))
    }

    private fun updateWeight(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: update_weight|name|new_weight", elapsed(start))
        }
        val valueName = parts[1].trim()
        val newWeight = parts[2].trim().toIntOrNull()
            ?: return PluginResult.error("Weight must be an integer (1-10)", elapsed(start))

        if (newWeight < 1 || newWeight > 10) {
            return PluginResult.error("Weight must be between 1 and 10", elapsed(start))
        }

        val values = getValues()
        var found = false

        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            if (v.getString("name").equals(valueName, ignoreCase = true)) {
                v.put("weight", newWeight)
                found = true
                break
            }
        }

        if (!found) {
            return PluginResult.error("Value not found: $valueName", elapsed(start))
        }

        saveValues(values)
        return PluginResult.success("Updated '$valueName' weight to $newWeight", elapsed(start))
    }

    private fun report(start: Long): PluginResult {
        val values = getValues()
        if (values.length() == 0) {
            return PluginResult.success("No values defined.", elapsed(start))
        }

        val sorted = mutableListOf<JSONObject>()
        for (i in 0 until values.length()) {
            sorted.add(values.getJSONObject(i))
        }
        sorted.sortByDescending { it.getInt("weight") }

        val sb = StringBuilder("Value Report (sorted by weight):\n")
        sorted.forEachIndexed { index, v ->
            sb.append("  ${index + 1}. ${v.getString("name")} [weight: ${v.getInt("weight")}]\n")
            sb.append("     ${v.getString("description")}\n")
        }

        val totalWeight = sorted.sumOf { it.getInt("weight") }
        sb.append("Total weight: $totalWeight across ${sorted.size} value(s)")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun getValues(): JSONArray {
        val raw = preferences.getString(KEY_VALUES, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveValues(values: JSONArray) {
        preferences.edit().putString(KEY_VALUES, values.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
