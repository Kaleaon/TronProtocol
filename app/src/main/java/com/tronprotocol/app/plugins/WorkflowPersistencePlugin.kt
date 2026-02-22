package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Save, load, and share multi-step workflows.
 * If a user teaches the agent a process once, it can repeat it.
 *
 * Commands:
 *   save|name|description          – Save current ReAct plan as reusable workflow
 *   save_steps|name|steps_json     – Save workflow from step definitions
 *   load|name                      – Load a saved workflow
 *   list                           – List all saved workflows
 *   delete|name                    – Delete a workflow
 *   export|name                    – Export workflow as shareable JSON
 *   import|json                    – Import workflow from JSON
 */
class WorkflowPersistencePlugin : Plugin {

    override val id: String = ID
    override val name: String = "Workflow Persistence"
    override val description: String =
        "Save/load reusable workflows. Commands: save|name|desc, save_steps|name|steps_json, load|name, list, delete|name, export|name, import|json"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "save_steps" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: save_steps|name|steps_json", elapsed(start))
                    val name = parts[1].trim()
                    val workflow = JSONObject().apply {
                        put("name", name)
                        put("steps", JSONArray(parts[2].trim()))
                        put("created", System.currentTimeMillis())
                        put("run_count", 0)
                    }
                    prefs.edit().putString("wf_$name", workflow.toString()).apply()
                    trackName(name)
                    PluginResult.success("Workflow saved: $name", elapsed(start))
                }
                "save" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: save|name|description", elapsed(start))
                    val name = parts[1].trim()
                    val workflow = JSONObject().apply {
                        put("name", name)
                        put("description", parts[2].trim())
                        put("steps", JSONArray())
                        put("created", System.currentTimeMillis())
                        put("run_count", 0)
                    }
                    prefs.edit().putString("wf_$name", workflow.toString()).apply()
                    trackName(name)
                    PluginResult.success("Workflow template saved: $name (add steps with save_steps)", elapsed(start))
                }
                "load" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: load|name", elapsed(start))
                    val str = prefs.getString("wf_$name", null)
                        ?: return PluginResult.error("Workflow not found: $name", elapsed(start))
                    val workflow = JSONObject(str)
                    workflow.put("run_count", workflow.optInt("run_count", 0) + 1)
                    prefs.edit().putString("wf_$name", workflow.toString()).apply()
                    PluginResult.success(workflow.toString(2), elapsed(start))
                }
                "list" -> {
                    val names = getNames()
                    val arr = JSONArray()
                    for (name in names) {
                        val str = prefs.getString("wf_$name", null) ?: continue
                        val wf = JSONObject(str)
                        arr.put(JSONObject().apply {
                            put("name", name)
                            put("description", wf.optString("description", ""))
                            put("steps", wf.optJSONArray("steps")?.length() ?: 0)
                            put("run_count", wf.optInt("run_count", 0))
                        })
                    }
                    PluginResult.success("Workflows (${arr.length()}):\n${arr.toString(2)}", elapsed(start))
                }
                "delete" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: delete|name", elapsed(start))
                    prefs.edit().remove("wf_$name").apply()
                    val names = getNames().toMutableSet()
                    names.remove(name)
                    prefs.edit().putStringSet("workflow_names", names).apply()
                    PluginResult.success("Deleted: $name", elapsed(start))
                }
                "export" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: export|name", elapsed(start))
                    val str = prefs.getString("wf_$name", null)
                        ?: return PluginResult.error("Workflow not found", elapsed(start))
                    PluginResult.success(str, elapsed(start))
                }
                "import" -> {
                    val json = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: import|json", elapsed(start))
                    val workflow = JSONObject(json)
                    val name = workflow.getString("name")
                    prefs.edit().putString("wf_$name", workflow.toString()).apply()
                    trackName(name)
                    PluginResult.success("Imported: $name", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Workflow error: ${e.message}", elapsed(start))
        }
    }

    private fun getNames(): Set<String> = prefs.getStringSet("workflow_names", emptySet()) ?: emptySet()

    private fun trackName(name: String) {
        val names = getNames().toMutableSet()
        names.add(name)
        prefs.edit().putStringSet("workflow_names", names).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("workflow_persistence", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "workflow_persistence"
    }
}
