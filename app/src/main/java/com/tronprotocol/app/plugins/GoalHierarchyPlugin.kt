package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Goal hierarchy plugin that manages a tree of goals with parent-child relationships.
 *
 * Top-level goals decompose into subgoals, forming a navigable hierarchy.
 * Goals track status (pending, in_progress, completed) and creation timestamps.
 *
 * Commands:
 *   add|name|parent_id(optional) - Add a new goal, optionally under a parent
 *   list                         - List all goals
 *   tree                         - Show indented goal hierarchy
 *   status|goal_id               - Get status of a specific goal
 *   complete|goal_id             - Mark a goal as completed
 *   remove|goal_id               - Remove a goal and its children
 *   progress                     - Show overall completion percentage
 */
class GoalHierarchyPlugin : Plugin {

    companion object {
        private const val ID = "goal_hierarchy"
        private const val PREFS = "goal_hierarchy_plugin"
        private const val KEY_GOALS = "goals_json"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Goal Hierarchy"

    override val description: String =
        "Goal tree manager. Commands: add|name|parent_id, list, tree, status|goal_id, complete|goal_id, remove|goal_id, progress"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add" -> addGoal(parts, start)
                "list" -> listGoals(start)
                "tree" -> showTree(start)
                "status" -> getStatus(parts, start)
                "complete" -> completeGoal(parts, start)
                "remove" -> removeGoal(parts, start)
                "progress" -> showProgress(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: add|name|parent_id, list, tree, status|goal_id, complete|goal_id, remove|goal_id, progress",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Goal hierarchy failed: ${e.message}", elapsed(start))
        }
    }

    private fun addGoal(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: add|name|parent_id(optional)", elapsed(start))
        }
        val name = parts[1].trim()
        val parentId = if (parts.size >= 3 && parts[2].trim().isNotEmpty()) parts[2].trim() else ""

        val goals = getGoals()

        if (parentId.isNotEmpty()) {
            val parent = findGoal(goals, parentId)
                ?: return PluginResult.error("Parent goal not found: $parentId", elapsed(start))
        }

        val goal = JSONObject().apply {
            put("id", UUID.randomUUID().toString().substring(0, 8))
            put("name", name)
            put("parentId", parentId)
            put("status", "pending")
            put("created", System.currentTimeMillis())
        }
        goals.put(goal)
        saveGoals(goals)

        return PluginResult.success(
            "Created goal '${name}' [${goal.getString("id")}]" +
                    if (parentId.isNotEmpty()) " under parent $parentId" else " (top-level)",
            elapsed(start)
        )
    }

    private fun listGoals(start: Long): PluginResult {
        val goals = getGoals()
        if (goals.length() == 0) {
            return PluginResult.success("No goals defined.", elapsed(start))
        }
        val sb = StringBuilder("Goals (${goals.length()}):\n")
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            sb.append("  [${g.getString("id")}] ${g.getString("name")} " +
                    "(${g.getString("status")})" +
                    if (g.optString("parentId", "").isNotEmpty()) " -> parent: ${g.getString("parentId")}" else "")
            sb.append("\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun showTree(start: Long): PluginResult {
        val goals = getGoals()
        if (goals.length() == 0) {
            return PluginResult.success("No goals defined.", elapsed(start))
        }

        val goalList = mutableListOf<JSONObject>()
        for (i in 0 until goals.length()) {
            goalList.add(goals.getJSONObject(i))
        }

        val sb = StringBuilder("Goal Hierarchy:\n")
        val roots = goalList.filter { it.optString("parentId", "").isEmpty() }
        for (root in roots) {
            buildTreeString(sb, goalList, root, 0)
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun buildTreeString(
        sb: StringBuilder,
        allGoals: List<JSONObject>,
        current: JSONObject,
        depth: Int
    ) {
        val indent = "  ".repeat(depth)
        val statusIcon = when (current.getString("status")) {
            "completed" -> "[x]"
            "in_progress" -> "[-]"
            else -> "[ ]"
        }
        sb.append("$indent$statusIcon ${current.getString("name")} (${current.getString("id")})\n")

        val children = allGoals.filter {
            it.optString("parentId", "") == current.getString("id")
        }
        for (child in children) {
            buildTreeString(sb, allGoals, child, depth + 1)
        }
    }

    private fun getStatus(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: status|goal_id", elapsed(start))
        }
        val goalId = parts[1].trim()
        val goals = getGoals()
        val goal = findGoal(goals, goalId)
            ?: return PluginResult.error("Goal not found: $goalId", elapsed(start))

        return PluginResult.success(
            "Goal '${goal.getString("name")}' [${goal.getString("id")}]: ${goal.getString("status")}",
            elapsed(start)
        )
    }

    private fun completeGoal(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: complete|goal_id", elapsed(start))
        }
        val goalId = parts[1].trim()
        val goals = getGoals()
        val goal = findGoal(goals, goalId)
            ?: return PluginResult.error("Goal not found: $goalId", elapsed(start))

        goal.put("status", "completed")
        goal.put("completedAt", System.currentTimeMillis())
        saveGoals(goals)

        return PluginResult.success(
            "Completed goal '${goal.getString("name")}' [${goal.getString("id")}]",
            elapsed(start)
        )
    }

    private fun removeGoal(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: remove|goal_id", elapsed(start))
        }
        val goalId = parts[1].trim()
        val goals = getGoals()

        val idsToRemove = mutableSetOf<String>()
        collectChildIds(goals, goalId, idsToRemove)
        idsToRemove.add(goalId)

        val found = findGoal(goals, goalId) != null
        if (!found) {
            return PluginResult.error("Goal not found: $goalId", elapsed(start))
        }

        val newGoals = JSONArray()
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            if (g.getString("id") !in idsToRemove) {
                newGoals.put(g)
            }
        }
        saveGoals(newGoals)

        return PluginResult.success(
            "Removed goal $goalId and ${idsToRemove.size - 1} child goal(s)",
            elapsed(start)
        )
    }

    private fun showProgress(start: Long): PluginResult {
        val goals = getGoals()
        if (goals.length() == 0) {
            return PluginResult.success("No goals defined. Progress: 0%", elapsed(start))
        }
        var total = 0
        var completed = 0
        for (i in 0 until goals.length()) {
            total++
            if (goals.getJSONObject(i).getString("status") == "completed") {
                completed++
            }
        }
        val percentage = if (total > 0) (completed * 100) / total else 0
        return PluginResult.success(
            "Progress: $completed/$total goals completed ($percentage%)",
            elapsed(start)
        )
    }

    private fun findGoal(goals: JSONArray, goalId: String): JSONObject? {
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            if (g.getString("id") == goalId) return g
        }
        return null
    }

    private fun collectChildIds(goals: JSONArray, parentId: String, ids: MutableSet<String>) {
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            if (g.optString("parentId", "") == parentId) {
                val childId = g.getString("id")
                ids.add(childId)
                collectChildIds(goals, childId, ids)
            }
        }
    }

    private fun getGoals(): JSONArray {
        val raw = preferences.getString(KEY_GOALS, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveGoals(goals: JSONArray) {
        preferences.edit().putString(KEY_GOALS, goals.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
