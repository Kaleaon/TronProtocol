package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 6 Self-Improvement: Prompt Strategy Evolution Plugin.
 *
 * Tracks which prompt strategies work best for different task types,
 * records success/failure outcomes, computes success rates, and evolves
 * strategies by recommending the best-performing approach for each task.
 *
 * Commands:
 *   register|<task_type>|<strategy>|<description>  - Register a strategy for a task type
 *   record_outcome|<task_type>|<strategy>|<success> - Record outcome (true/false) for a strategy
 *   best|<task_type>                                 - Get the best strategy for a task type
 *   evolve|<task_type>                               - Analyze outcomes and recommend evolved strategy
 *   list                                             - List all registered strategies
 *   clear                                            - Clear all strategy data
 */
class PromptStrategyEvolutionPlugin : Plugin {

    companion object {
        private const val ID = "prompt_strategy_evolution"
        private const val TAG = "PromptStrategyEvolution"
        private const val PREFS_NAME = "tronprotocol_prompt_strategies"
        private const val KEY_STRATEGIES = "strategy_data"
    }

    private var prefs: SharedPreferences? = null

    // Map<taskType, Map<strategyName, StrategyRecord>>
    private val strategyMap = mutableMapOf<String, MutableMap<String, StrategyRecord>>()

    override val id: String = ID

    override val name: String = "Prompt Strategy Evolution"

    override val description: String =
        "Track and evolve prompt strategies. Commands: register|task_type|strategy|description, " +
            "record_outcome|task_type|strategy|success_bool, best|task_type, evolve|task_type, list, clear"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "register" -> handleRegister(parts, start)
                "record_outcome" -> handleRecordOutcome(parts, start)
                "best" -> handleBest(parts, start)
                "evolve" -> handleEvolve(parts, start)
                "list" -> handleList(start)
                "clear" -> handleClear(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: register|task_type|strategy|description, " +
                        "record_outcome|task_type|strategy|success_bool, best|task_type, evolve|task_type, list, clear",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in PromptStrategyEvolutionPlugin", e)
            PluginResult.error("Prompt strategy evolution failed: ${e.message}", elapsed(start))
        }
    }

    private fun handleRegister(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty() || parts[3].trim().isEmpty()) {
            return PluginResult.error("Usage: register|task_type|strategy|description", elapsed(start))
        }
        val taskType = parts[1].trim()
        val strategy = parts[2].trim()
        val desc = parts[3].trim()

        val strategies = strategyMap.getOrPut(taskType) { mutableMapOf() }

        if (strategies.containsKey(strategy)) {
            strategies[strategy]!!.description = desc
            saveStrategies()
            return PluginResult.success(
                "Updated strategy '$strategy' for task '$taskType': $desc",
                elapsed(start)
            )
        }

        strategies[strategy] = StrategyRecord(
            description = desc,
            successes = 0,
            failures = 0,
            createdAt = System.currentTimeMillis()
        )
        saveStrategies()

        return PluginResult.success(
            "Registered strategy '$strategy' for task '$taskType': $desc (${strategies.size} strategy/ies for this task)",
            elapsed(start)
        )
    }

    private fun handleRecordOutcome(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty() || parts[3].trim().isEmpty()) {
            return PluginResult.error("Usage: record_outcome|task_type|strategy|success_bool", elapsed(start))
        }
        val taskType = parts[1].trim()
        val strategy = parts[2].trim()
        val successStr = parts[3].trim().lowercase()

        val success = when (successStr) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> return PluginResult.error(
                "Invalid success value '$successStr'. Use true/false.",
                elapsed(start)
            )
        }

        val strategies = strategyMap.getOrPut(taskType) { mutableMapOf() }
        val record = strategies.getOrPut(strategy) {
            StrategyRecord(
                description = "(auto-registered)",
                successes = 0,
                failures = 0,
                createdAt = System.currentTimeMillis()
            )
        }

        if (success) record.successes++ else record.failures++
        saveStrategies()

        val total = record.successes + record.failures
        val rate = if (total > 0) (record.successes * 100.0 / total) else 0.0

        return PluginResult.success(
            "Recorded ${if (success) "success" else "failure"} for '$strategy' on '$taskType'. " +
                "Success rate: ${"%.1f".format(rate)}% (${record.successes}/${total})",
            elapsed(start)
        )
    }

    private fun handleBest(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: best|task_type", elapsed(start))
        }
        val taskType = parts[1].trim()
        val strategies = strategyMap[taskType]

        if (strategies == null || strategies.isEmpty()) {
            return PluginResult.success(
                "No strategies registered for task '$taskType'.",
                elapsed(start)
            )
        }

        val withTrials = strategies.filter { (it.value.successes + it.value.failures) > 0 }
        if (withTrials.isEmpty()) {
            return PluginResult.success(
                "No outcome data recorded for task '$taskType'. ${strategies.size} strategy/ies registered but untested.",
                elapsed(start)
            )
        }

        val best = withTrials.maxByOrNull { entry ->
            val total = entry.value.successes + entry.value.failures
            entry.value.successes.toDouble() / total
        }!!

        val total = best.value.successes + best.value.failures
        val rate = best.value.successes * 100.0 / total

        return PluginResult.success(
            "Best strategy for '$taskType': '${best.key}' with ${"%.1f".format(rate)}% success rate " +
                "(${best.value.successes}/$total). Description: ${best.value.description}",
            elapsed(start)
        )
    }

    private fun handleEvolve(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: evolve|task_type", elapsed(start))
        }
        val taskType = parts[1].trim()
        val strategies = strategyMap[taskType]

        if (strategies == null || strategies.isEmpty()) {
            return PluginResult.success(
                "No strategies registered for task '$taskType'. Register strategies first.",
                elapsed(start)
            )
        }

        val ranked = strategies.entries
            .filter { (it.value.successes + it.value.failures) > 0 }
            .sortedByDescending { entry ->
                val total = entry.value.successes + entry.value.failures
                entry.value.successes.toDouble() / total
            }

        if (ranked.isEmpty()) {
            return PluginResult.success(
                "No outcome data for task '$taskType'. Record outcomes before evolving.",
                elapsed(start)
            )
        }

        val sb = buildString {
            append("Strategy Evolution Analysis for '$taskType':\n\n")
            append("Ranked strategies (best to worst):\n")
            ranked.forEachIndexed { index, entry ->
                val total = entry.value.successes + entry.value.failures
                val rate = entry.value.successes * 100.0 / total
                val status = when {
                    rate >= 80.0 -> "STRONG"
                    rate >= 50.0 -> "VIABLE"
                    rate >= 20.0 -> "WEAK"
                    else -> "FAILING"
                }
                append("  ${index + 1}. [$status] '${entry.key}' - ${"%.1f".format(rate)}% " +
                    "(${entry.value.successes}/$total) - ${entry.value.description}\n")
            }

            val best = ranked.first()
            val bestTotal = best.value.successes + best.value.failures
            val bestRate = best.value.successes * 100.0 / bestTotal

            append("\nRecommendation: ")
            if (bestRate >= 80.0 && bestTotal >= 5) {
                append("Strategy '${best.key}' is performing strongly. Continue using it as the primary approach.")
            } else if (bestRate >= 50.0) {
                append("Strategy '${best.key}' leads but has room for improvement. Consider refining or combining with elements of other strategies.")
            } else {
                append("No strategy is performing well. Consider registering new approaches or fundamentally revising existing ones.")
            }

            // Flag untested strategies
            val untested = strategies.entries.filter { (it.value.successes + it.value.failures) == 0 }
            if (untested.isNotEmpty()) {
                append("\n\nUntested strategies: ${untested.joinToString(", ") { "'${it.key}'" }}. " +
                    "Consider testing these for potential improvements.")
            }
        }

        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleList(start: Long): PluginResult {
        if (strategyMap.isEmpty()) {
            return PluginResult.success("No strategies registered.", elapsed(start))
        }

        val sb = buildString {
            append("Prompt Strategies (${strategyMap.size} task type(s)):\n")
            for ((taskType, strategies) in strategyMap) {
                append("\n[$taskType] (${strategies.size} strategy/ies):\n")
                strategies.forEach { (name, record) ->
                    val total = record.successes + record.failures
                    val rate = if (total > 0) "${"%.1f".format(record.successes * 100.0 / total)}% (${record.successes}/$total)" else "no data"
                    append("  - '$name': $rate - ${record.description}\n")
                }
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleClear(start: Long): PluginResult {
        val totalTasks = strategyMap.size
        val totalStrategies = strategyMap.values.sumOf { it.size }
        strategyMap.clear()
        saveStrategies()
        return PluginResult.success(
            "Cleared $totalStrategies strategy/ies across $totalTasks task type(s).",
            elapsed(start)
        )
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadStrategies()
        Log.d(TAG, "PromptStrategyEvolutionPlugin initialized with ${strategyMap.size} task type(s)")
    }

    override fun destroy() {
        strategyMap.clear()
        prefs = null
    }

    // ---- Persistence ----

    private fun saveStrategies() {
        val root = JSONObject()
        for ((taskType, strategies) in strategyMap) {
            val taskObj = JSONObject()
            for ((name, record) in strategies) {
                val recObj = JSONObject()
                recObj.put("description", record.description)
                recObj.put("successes", record.successes)
                recObj.put("failures", record.failures)
                recObj.put("createdAt", record.createdAt)
                taskObj.put(name, recObj)
            }
            root.put(taskType, taskObj)
        }
        prefs?.edit()?.putString(KEY_STRATEGIES, root.toString())?.apply()
    }

    private fun loadStrategies() {
        val data = prefs?.getString(KEY_STRATEGIES, null) ?: return
        try {
            val root = JSONObject(data)
            strategyMap.clear()
            for (taskType in root.keys()) {
                val taskObj = root.getJSONObject(taskType)
                val strategies = mutableMapOf<String, StrategyRecord>()
                for (name in taskObj.keys()) {
                    val recObj = taskObj.getJSONObject(name)
                    strategies[name] = StrategyRecord(
                        description = recObj.getString("description"),
                        successes = recObj.getInt("successes"),
                        failures = recObj.getInt("failures"),
                        createdAt = recObj.getLong("createdAt")
                    )
                }
                strategyMap[taskType] = strategies
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading strategies", e)
        }
    }

    // ---- Data class ----

    private data class StrategyRecord(
        var description: String,
        var successes: Int,
        var failures: Int,
        val createdAt: Long
    )
}
