package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

/**
 * Central policy guardrail plugin used by PluginManager before plugin execution.
 */
class PolicyGuardrailPlugin : Plugin {

    companion object {
        private const val ID = "policy_guardrail"
        private const val PREFS = "policy_guardrail_plugin"
        private const val KEY_DENIED = "denied_plugins"
        private const val KEY_BLOCKED_PATTERNS = "blocked_patterns"
    }

    private lateinit var preferences: SharedPreferences
    override val id: String = ID

    override val name: String = "Policy Guardrail"

    override val description: String =
        "Policy gate for plugin execution. Commands: deny_plugin|id, allow_plugin|id, list_denied, " +
            "add_pattern|text, remove_pattern|text, list_patterns, check|pluginId|input"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start))
            }
            val parts = input.split("\\|".toRegex(), 3).toTypedArray()
            val command = parts[0].trim().lowercase()
            when (command) {
                "deny_plugin" -> denyPlugin(parts, start)
                "allow_plugin" -> allowPlugin(parts, start)
                "list_denied" -> PluginResult.success("Denied plugins: ${getDeniedPlugins()}", elapsed(start))
                "add_pattern" -> addPattern(parts, start)
                "remove_pattern" -> removePattern(parts, start)
                "list_patterns" -> PluginResult.success("Blocked patterns: ${getBlockedPatterns()}", elapsed(start))
                "check" -> check(parts, start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Policy command failed: ${e.message}", elapsed(start))
        }
    }

    fun evaluate(pluginId: String, input: String): PluginResult {
        val start = System.currentTimeMillis()
        if (TextUtils.isEmpty(pluginId)) {
            return PluginResult.error("Plugin ID missing", elapsed(start))
        }

        if (getDeniedPlugins().contains(pluginId)) {
            return PluginResult.error("Policy blocked plugin: $pluginId", elapsed(start))
        }

        if (!TextUtils.isEmpty(input)) {
            val lowered = input.lowercase()
            for (pattern in getBlockedPatterns()) {
                if (!TextUtils.isEmpty(pattern) && lowered.contains(pattern.lowercase())) {
                    return PluginResult.error("Policy blocked input pattern: $pattern", elapsed(start))
                }
            }
        }

        return PluginResult.success("Allowed", elapsed(start))
    }

    private fun check(parts: Array<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: check|<plugin_id>|<input_optional>", elapsed(start))
        }
        val pluginId = parts[1].trim()
        val payload = if (parts.size >= 3) parts[2] else ""
        return evaluate(pluginId, payload)
    }

    private fun denyPlugin(parts: Array<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: deny_plugin|<plugin_id>", elapsed(start))
        }
        val denied = getDeniedPlugins()
        denied.add(parts[1].trim())
        preferences.edit().putStringSet(KEY_DENIED, denied).apply()
        return PluginResult.success("Denied plugin: ${parts[1].trim()}", elapsed(start))
    }

    private fun allowPlugin(parts: Array<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: allow_plugin|<plugin_id>", elapsed(start))
        }
        val denied = getDeniedPlugins()
        denied.remove(parts[1].trim())
        preferences.edit().putStringSet(KEY_DENIED, denied).apply()
        return PluginResult.success("Allowed plugin: ${parts[1].trim()}", elapsed(start))
    }

    private fun addPattern(parts: Array<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: add_pattern|<text>", elapsed(start))
        }
        val patterns = getBlockedPatterns()
        patterns.add(parts[1].trim())
        preferences.edit().putStringSet(KEY_BLOCKED_PATTERNS, patterns).apply()
        return PluginResult.success("Added blocked pattern", elapsed(start))
    }

    private fun removePattern(parts: Array<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: remove_pattern|<text>", elapsed(start))
        }
        val patterns = getBlockedPatterns()
        patterns.remove(parts[1].trim())
        preferences.edit().putStringSet(KEY_BLOCKED_PATTERNS, patterns).apply()
        return PluginResult.success("Removed blocked pattern", elapsed(start))
    }

    private fun getDeniedPlugins(): MutableSet<String> =
        HashSet(preferences.getStringSet(KEY_DENIED, emptySet()) ?: emptySet())

    private fun getBlockedPatterns(): MutableSet<String> {
        val defaults = hashSetOf("rm -rf", "drop table", "format /", "shutdown")
        return HashSet(preferences.getStringSet(KEY_BLOCKED_PATTERNS, defaults) ?: defaults)
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
