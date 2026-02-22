package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ReAct-style (Reason + Act) multi-step planning executor.
 * Chains multiple plugin calls into a plan, executes steps sequentially,
 * observes results, and adjusts. The orchestration layer that turns
 * isolated plugins into coordinated workflows.
 *
 * Commands:
 *   plan|goal                   – Create a new plan for a goal
 *   add_step|planId|plugin|input – Add a step to a plan
 *   execute|planId              – Execute all pending steps in order
 *   step|planId                 – Execute only the next pending step
 *   status|planId               – Get plan status
 *   list                        – List all plans
 *   abort|planId                – Abort a running plan
 *   history|count               – Completed plan history
 */
class ReActPlanningExecutor : Plugin {

    override val id: String = ID
    override val name: String = "ReAct Planning Executor"
    override val description: String =
        "Multi-step tool chaining. Commands: plan|goal, add_step|planId|plugin|input, execute|planId, step|planId, status|planId, list, abort|planId, history|count"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences
    private var pluginManager: PluginManager? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "plan" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: plan|goal", elapsed(start))
                    val planId = "plan_${System.currentTimeMillis()}"
                    val plan = JSONObject().apply {
                        put("id", planId)
                        put("goal", parts[1].trim())
                        put("status", "planning")
                        put("created", System.currentTimeMillis())
                        put("steps", JSONArray())
                        put("current_step", 0)
                        put("results", JSONArray())
                    }
                    savePlan(planId, plan)
                    PluginResult.success("Plan created: $planId\nGoal: ${parts[1].trim()}\nAdd steps with: add_step|$planId|plugin_id|input", elapsed(start))
                }
                "add_step" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: add_step|planId|plugin|input", elapsed(start))
                    val planId = parts[1].trim()
                    val plan = loadPlan(planId)
                        ?: return PluginResult.error("Plan not found: $planId", elapsed(start))
                    val steps = plan.getJSONArray("steps")
                    steps.put(JSONObject().apply {
                        put("step_num", steps.length())
                        put("plugin", parts[2].trim())
                        put("input", parts[3].trim())
                        put("status", "pending")
                        put("depends_on_previous", steps.length() > 0)
                    })
                    savePlan(planId, plan)
                    PluginResult.success("Step ${steps.length() - 1} added to $planId: ${parts[2].trim()}", elapsed(start))
                }
                "execute" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: execute|planId", elapsed(start))
                    executePlan(parts[1].trim(), start)
                }
                "step" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: step|planId", elapsed(start))
                    executeNextStep(parts[1].trim(), start)
                }
                "status" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: status|planId", elapsed(start))
                    val plan = loadPlan(parts[1].trim())
                        ?: return PluginResult.error("Plan not found", elapsed(start))
                    PluginResult.success(plan.toString(2), elapsed(start))
                }
                "list" -> {
                    val plans = loadAllPlanIds()
                    val summary = JSONArray()
                    for (id in plans) {
                        val plan = loadPlan(id) ?: continue
                        summary.put(JSONObject().apply {
                            put("id", id)
                            put("goal", plan.optString("goal"))
                            put("status", plan.optString("status"))
                            put("steps", plan.optJSONArray("steps")?.length() ?: 0)
                            put("current_step", plan.optInt("current_step"))
                        })
                    }
                    PluginResult.success("Plans (${summary.length()}):\n${summary.toString(2)}", elapsed(start))
                }
                "abort" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: abort|planId", elapsed(start))
                    val plan = loadPlan(parts[1].trim())
                        ?: return PluginResult.error("Plan not found", elapsed(start))
                    plan.put("status", "aborted")
                    savePlan(parts[1].trim(), plan)
                    PluginResult.success("Plan aborted: ${parts[1].trim()}", elapsed(start))
                }
                "history" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 10
                    val plans = loadAllPlanIds()
                    val completed = mutableListOf<JSONObject>()
                    for (id in plans) {
                        val plan = loadPlan(id) ?: continue
                        val status = plan.optString("status")
                        if (status == "completed" || status == "failed" || status == "aborted") {
                            completed.add(JSONObject().apply {
                                put("id", id)
                                put("goal", plan.optString("goal"))
                                put("status", status)
                                put("steps", plan.optJSONArray("steps")?.length() ?: 0)
                            })
                        }
                    }
                    val arr = JSONArray()
                    completed.takeLast(count).forEach { arr.put(it) }
                    PluginResult.success("History (${arr.length()}):\n${arr.toString(2)}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("ReAct error: ${e.message}", elapsed(start))
        }
    }

    private fun executePlan(planId: String, outerStart: Long): PluginResult {
        val plan = loadPlan(planId)
            ?: return PluginResult.error("Plan not found: $planId", elapsed(outerStart))
        val steps = plan.getJSONArray("steps")
        if (steps.length() == 0) {
            return PluginResult.error("Plan has no steps", elapsed(outerStart))
        }

        plan.put("status", "running")
        savePlan(planId, plan)

        val results = plan.optJSONArray("results") ?: JSONArray()
        var currentStep = plan.optInt("current_step", 0)

        while (currentStep < steps.length()) {
            val step = steps.getJSONObject(currentStep)
            val pluginId = step.getString("plugin")
            var stepInput = step.getString("input")

            // Template substitution: replace {prev_result} with previous step output
            if (currentStep > 0 && stepInput.contains("{prev_result}")) {
                val prevResult = results.optJSONObject(results.length() - 1)
                val prevData = prevResult?.optString("data", "") ?: ""
                stepInput = stepInput.replace("{prev_result}", prevData)
            }

            step.put("status", "running")
            step.put("actual_input", stepInput)
            savePlan(planId, plan)

            val pm = pluginManager ?: PluginManager.getInstance()
            val result = pm.executePlugin(pluginId, stepInput)

            val stepResult = JSONObject().apply {
                put("step", currentStep)
                put("plugin", pluginId)
                put("success", result.isSuccess)
                put("data", result.data ?: result.errorMessage ?: "")
                put("duration_ms", result.executionTimeMs)
            }
            results.put(stepResult)

            if (result.isSuccess) {
                step.put("status", "completed")
            } else {
                step.put("status", "failed")
                step.put("error", result.errorMessage)
                plan.put("status", "failed")
                plan.put("failed_at_step", currentStep)
                plan.put("results", results)
                plan.put("current_step", currentStep)
                savePlan(planId, plan)
                Log.w(TAG, "Plan $planId failed at step $currentStep: ${result.errorMessage}")
                return PluginResult.success("Plan failed at step $currentStep:\n${stepResult.toString(2)}", elapsed(outerStart))
            }

            currentStep++
            plan.put("current_step", currentStep)
            plan.put("results", results)
            savePlan(planId, plan)
        }

        plan.put("status", "completed")
        plan.put("completed_at", System.currentTimeMillis())
        savePlan(planId, plan)

        return PluginResult.success("Plan completed ($currentStep steps):\n${results.toString(2)}", elapsed(outerStart))
    }

    private fun executeNextStep(planId: String, outerStart: Long): PluginResult {
        val plan = loadPlan(planId)
            ?: return PluginResult.error("Plan not found", elapsed(outerStart))
        val steps = plan.getJSONArray("steps")
        val currentStep = plan.optInt("current_step", 0)

        if (currentStep >= steps.length()) {
            return PluginResult.error("No more steps to execute", elapsed(outerStart))
        }

        val step = steps.getJSONObject(currentStep)
        val pluginId = step.getString("plugin")
        var stepInput = step.getString("input")

        val results = plan.optJSONArray("results") ?: JSONArray()
        if (currentStep > 0 && stepInput.contains("{prev_result}")) {
            val prevResult = results.optJSONObject(results.length() - 1)
            val prevData = prevResult?.optString("data", "") ?: ""
            stepInput = stepInput.replace("{prev_result}", prevData)
        }

        plan.put("status", "running")
        step.put("status", "running")
        savePlan(planId, plan)

        val pm = pluginManager ?: PluginManager.getInstance()
        val result = pm.executePlugin(pluginId, stepInput)

        val stepResult = JSONObject().apply {
            put("step", currentStep)
            put("plugin", pluginId)
            put("success", result.isSuccess)
            put("data", result.data ?: result.errorMessage ?: "")
            put("duration_ms", result.executionTimeMs)
        }
        results.put(stepResult)

        step.put("status", if (result.isSuccess) "completed" else "failed")
        plan.put("current_step", currentStep + 1)
        plan.put("results", results)

        if (currentStep + 1 >= steps.length()) {
            plan.put("status", if (result.isSuccess) "completed" else "failed")
        } else {
            plan.put("status", if (result.isSuccess) "running" else "failed")
        }

        savePlan(planId, plan)
        return PluginResult.success("Step $currentStep result:\n${stepResult.toString(2)}", elapsed(outerStart))
    }

    private fun loadPlan(planId: String): JSONObject? {
        val str = prefs.getString("plan_$planId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    private fun savePlan(planId: String, plan: JSONObject) {
        prefs.edit().putString("plan_$planId", plan.toString()).apply()
        // Track plan ID
        val ids = loadAllPlanIds().toMutableSet()
        ids.add(planId)
        prefs.edit().putStringSet("plan_ids", ids).apply()
    }

    private fun loadAllPlanIds(): Set<String> =
        prefs.getStringSet("plan_ids", emptySet()) ?: emptySet()

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("react_planner", Context.MODE_PRIVATE)
        pluginManager = PluginManager.getInstance()
    }

    override fun destroy() {
        pluginManager = null
    }

    companion object {
        const val ID = "react_planner"
        private const val TAG = "ReActPlanner"
    }
}
