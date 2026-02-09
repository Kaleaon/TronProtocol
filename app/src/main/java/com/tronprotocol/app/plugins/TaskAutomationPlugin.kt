package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Simple task automation plugin for queueing and tracking actionable tasks.
 */
class TaskAutomationPlugin : Plugin {

    companion object {
        private const val ID = "task_automation"
        private const val PREFS = "task_automation_plugin"
        private const val KEY_TASKS = "tasks_json"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Task Automation"

    override val description: String =
        "Task queue automation. Commands: create|title|details|dueEpochMs, list, due|nowEpochMs, run|taskId, complete|taskId"

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
