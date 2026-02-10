package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import org.json.JSONObject

/**
 * User preference/profile memory plugin with explicit commands.
 */
class PersonalizationPlugin : Plugin {

    companion object {
        private const val ID = "personalization"
        private const val PREFS = "personalization_plugin"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Personalization"

    override val description: String =
        "Preference memory store. Commands: set|key|value, get|key, list, forget|key, export_json, clear"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start))
            }
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: set|key|value", elapsed(start))
                    preferences.edit().putString(parts[1].trim(), parts[2].trim()).apply()
                    PluginResult.success("Saved key: ${parts[1].trim()}", elapsed(start))
                }
                "get" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: get|key", elapsed(start))
                    val value = preferences.getString(parts[1].trim(), null)
                        ?: return PluginResult.error("Key not found: ${parts[1].trim()}", elapsed(start))
                    PluginResult.success(value, elapsed(start))
                }
                "list" -> PluginResult.success(preferences.all.keys.toString(), elapsed(start))
                "forget" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: forget|key", elapsed(start))
                    preferences.edit().remove(parts[1].trim()).apply()
                    PluginResult.success("Removed key: ${parts[1].trim()}", elapsed(start))
                }
                "export_json" -> PluginResult.success(exportJson(), elapsed(start))
                "clear" -> {
                    preferences.edit().clear().apply()
                    PluginResult.success("Cleared personalization data", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Personalization failed: ${e.message}", elapsed(start))
        }
    }

    private fun exportJson(): String {
        val json = JSONObject()
        for ((key, value) in preferences.all) {
            json.put(key, value.toString())
        }
        return json.toString()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
