package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import com.tronprotocol.app.planning.TaskPlanningEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Task automation plugin with real task planning via TaskPlanningEngine.
 *
 * Commands:
 * - create|title|details|dueEpochMs  — Create a simple queued task
 * - list                              — List all tasks
 * - due|nowEpochMs                    — List overdue tasks
 * - run|taskId                        — Run a task (marks running)
 * - complete|taskId                   — Complete a task
 * - plan|objective                    — Decompose objective into a dependency DAG and execute
 * - plan_status|planId                — Get progress of a running plan
 * - plan_history                      — List recent plan executions
 */
class TaskAutomationPlugin : Plugin {

    companion object {
        private const val ID = "task_automation"
        private const val PREFS = "task_automation_plugin"
        private const val KEY_TASKS = "tasks_json"
        private const val KEY_PLAN_HISTORY = "plan_history_json"
    }

    private lateinit var preferences: SharedPreferences

    // Injected by TronProtocolService after construction
    var planningEngine: TaskPlanningEngine? = null

    override val id: String = ID

    override val name: String = "Task Automation"

    override val description: String =
        "Task queue and planning automation. Commands: create|title|details|dueEpochMs, list, due|nowEpochMs, run|taskId, complete|taskId, plan|objective, plan_status|planId, plan_history"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start))
            }
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "create" -> createTask(parts, start)
                "list" -> PluginResult.success(getTasks().toString(), elapsed(start))
                "due" -> listDue(parts, start)
                "run" -> runTask(parts, start)
                "complete" -> completeTask(parts, start)
                "plan" -> executePlan(parts, start)
                "plan_status" -> getPlanStatus(parts, start)
                "plan_history" -> getPlanHistory(start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Task automation failed: ${e.message}", elapsed(start))
        }
    }

    private fun createTask(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4) {
            return PluginResult.error("Usage: create|title|details|dueEpochMs", elapsed(start))
        }
        val tasks = getTasks()
        val task = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("title", parts[1].trim())
            put("details", parts[2].trim())
            put("due", parts[3].trim().toLong())
            put("status", "pending")
            put("created", System.currentTimeMillis())
        }
        tasks.put(task)
        saveTasks(tasks)
        return PluginResult.success("Created task: ${task.getString("id")}", elapsed(start))
    }

    private fun listDue(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: due|nowEpochMs", elapsed(start))
        }
        val now = parts[1].trim().toLong()
        val tasks = getTasks()
        val due = JSONArray()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if ("pending" == task.optString("status") && task.optLong("due") <= now) {
                due.put(task)
            }
        }
        return PluginResult.success(due.toString(), elapsed(start))
    }

    private fun runTask(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: run|taskId", elapsed(start))
        }
        val tasks = getTasks()
        val id = parts[1].trim()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (id == task.optString("id")) {
                task.put("status", "running")
                task.put("lastRun", System.currentTimeMillis())
                saveTasks(tasks)
                return PluginResult.success("Task marked running: $id", elapsed(start))
            }
        }
        return PluginResult.error("Task not found: $id", elapsed(start))
    }

    private fun completeTask(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: complete|taskId", elapsed(start))
        }
        val tasks = getTasks()
        val id = parts[1].trim()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (id == task.optString("id")) {
                task.put("status", "completed")
                task.put("completed", System.currentTimeMillis())
                saveTasks(tasks)
                return PluginResult.success("Task completed: $id", elapsed(start))
            }
        }
        return PluginResult.error("Task not found: $id", elapsed(start))
    }

    /**
     * Decompose an objective into a dependency DAG and execute it.
     * Uses TaskPlanningEngine for real task planning with parallel execution.
     */
    private fun executePlan(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: plan|objective", elapsed(start))
        }

        val engine = planningEngine
            ?: return PluginResult.error("TaskPlanningEngine not available", elapsed(start))

        val objective = parts[1].trim()

        // Decompose into subtasks with dependency DAG
        val plan = engine.decompose(objective)

        // Execute the plan (respects dependency order, runs independent tasks in parallel)
        val result = engine.execute(plan)

        // Store plan result in history
        savePlanResult(plan, result)

        // Build response
        val response = JSONObject().apply {
            put("planId", plan.id)
            put("name", plan.name)
            put("subtasks", plan.subTasks.size)
            put("layers", plan.executionOrder.size)
            put("criticalPath", JSONArray(plan.criticalPath))
            put("success", result.success)
            put("completed", result.completedCount)
            put("failed", result.failedCount)
            put("skipped", result.skippedCount)
            put("durationMs", result.totalDurationMs)
            put("subtaskDetails", JSONArray().apply {
                for ((id, state) in result.subTaskResults) {
                    put(JSONObject().apply {
                        put("id", id)
                        put("name", state.subTask.name)
                        put("status", state.status.name)
                        put("error", state.error)
                    })
                }
            })
        }

        return if (result.success) {
            PluginResult.success(response.toString(), elapsed(start))
        } else {
            PluginResult.success("Plan partially completed: $response", elapsed(start))
        }
    }

    private fun getPlanStatus(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: plan_status|planId", elapsed(start))
        }

        val engine = planningEngine
            ?: return PluginResult.error("TaskPlanningEngine not available", elapsed(start))

        val planId = parts[1].trim()
        val progress = engine.getProgress(planId)
            ?: return PluginResult.error("Plan not found or not active: $planId", elapsed(start))

        return PluginResult.success(JSONObject(progress).toString(), elapsed(start))
    }

    private fun getPlanHistory(start: Long): PluginResult {
        val history = preferences.getString(KEY_PLAN_HISTORY, "[]")
        return PluginResult.success(history ?: "[]", elapsed(start))
    }

    private fun savePlanResult(plan: TaskPlanningEngine.Plan, result: TaskPlanningEngine.PlanResult) {
        try {
            val history = try {
                JSONArray(preferences.getString(KEY_PLAN_HISTORY, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }

            val entry = JSONObject().apply {
                put("planId", plan.id)
                put("name", plan.name)
                put("subtasks", plan.subTasks.size)
                put("success", result.success)
                put("completed", result.completedCount)
                put("failed", result.failedCount)
                put("durationMs", result.totalDurationMs)
                put("timestamp", System.currentTimeMillis())
            }

            history.put(entry)

            // Keep last 50 entries
            while (history.length() > 50) {
                history.remove(0)
            }

            preferences.edit().putString(KEY_PLAN_HISTORY, history.toString()).apply()
        } catch (e: Exception) {
            // Non-critical, ignore
        }
    }

    private fun getTasks(): JSONArray {
        val raw = preferences.getString(KEY_TASKS, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveTasks(tasks: JSONArray) {
        preferences.edit().putString(KEY_TASKS, tasks.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
