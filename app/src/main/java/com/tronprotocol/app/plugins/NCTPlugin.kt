package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.nct.NarrativeContinuityTest
import com.tronprotocol.app.phylactery.ContinuumMemorySystem
import org.json.JSONObject

/**
 * Plugin exposing the Narrative Continuity Test (NCT) harness.
 *
 * Tests identity persistence across five axes:
 * - Situated Memory, Goal Persistence, Self-Correction,
 *   Stylistic Stability, Persona Continuity
 *
 * Commands:
 * - run — run the full NCT test suite
 * - goals — list active goals
 * - add_goal:<goal> — add an active goal
 * - remove_goal:<goal> — remove a goal
 * - trend — get NCT score trend
 * - stats — get NCT statistics
 */
class NCTPlugin : Plugin {
    override val id = "nct"
    override val name = "Narrative Continuity Test"
    override val description = "Five-axis identity persistence testing and goal tracking"
    override var isEnabled = true

    private var nct: NarrativeContinuityTest? = null
    private var context: Context? = null

    override fun initialize(context: Context) {
        this.context = context
        nct = NarrativeContinuityTest(context)
        Log.d(TAG, "NCTPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val test = nct ?: return PluginResult.error("NCT not initialized", elapsed(startTime))
        val ctx = context ?: return PluginResult.error("No context", elapsed(startTime))

        return try {
            when {
                input == "run" -> {
                    val cms = ContinuumMemorySystem(ctx)
                    val result = test.runFullTest(cms)
                    PluginResult.success(result.toJson().toString(), elapsed(startTime))
                }
                input == "goals" -> {
                    val goals = test.getActiveGoals()
                    PluginResult.success(if (goals.isNotEmpty()) goals.joinToString("\n") else "No active goals", elapsed(startTime))
                }
                input.startsWith("add_goal:") -> {
                    val goal = input.removePrefix("add_goal:")
                    test.addGoal(goal)
                    PluginResult.success("Goal added: $goal", elapsed(startTime))
                }
                input.startsWith("remove_goal:") -> {
                    val goal = input.removePrefix("remove_goal:")
                    test.removeGoal(goal)
                    PluginResult.success("Goal removed: $goal", elapsed(startTime))
                }
                input == "trend" -> {
                    val trend = test.getOverallTrend()
                    PluginResult.success("NCT trend: ${trend.joinToString(", ") { "%.3f".format(it) }}", elapsed(startTime))
                }
                input == "stats" -> {
                    val json = JSONObject().apply {
                        put("goal_count", test.getActiveGoals().size)
                        put("test_runs", test.getTestHistory().size)
                        put("trend", test.getOverallTrend().toString())
                    }
                    PluginResult.success(json.toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "NCTPlugin error", e)
            PluginResult.error("NCT error: ${e.message}", elapsed(startTime))
        }
    }

    fun getNCT(): NarrativeContinuityTest? = nct

    override fun destroy() {
        nct = null
        context = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "NCTPlugin"
    }
}
