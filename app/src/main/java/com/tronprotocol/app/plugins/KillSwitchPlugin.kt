package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kill Switch Plugin - Phase 8 Safety & Transparency.
 *
 * Emergency stop mechanism that can halt all plugin execution.
 * When engaged, the kill switch signals to all other plugins (via the
 * "check" command) that they should cease operation.  Disengaging
 * requires a valid auth code.
 *
 * Commands:
 *   status                    - Show current kill-switch state
 *   engage|reason             - Engage the kill switch with a reason
 *   disengage|auth_code       - Disengage the kill switch (requires auth code)
 *   set_auth|code             - Change the auth code
 *   check                     - Quick boolean check (engaged=true/false)
 *   history                   - Show engagement/disengagement history
 *   auto_trigger|condition    - Register an auto-trigger condition
 */
class KillSwitchPlugin : Plugin {

    companion object {
        private const val ID = "kill_switch"
        private const val PREFS_NAME = "tronprotocol_kill_switch"
        private const val KEY_ENGAGED = "engaged"
        private const val KEY_REASON = "engaged_reason"
        private const val KEY_AUTH_CODE = "auth_code"
        private const val KEY_HISTORY = "switch_history"
        private const val KEY_AUTO_TRIGGERS = "auto_triggers"
        private const val DEFAULT_AUTH_CODE = "TRON_OVERRIDE"
    }

    private lateinit var prefs: SharedPreferences
    private var engaged: Boolean = false
    private var engagedReason: String = ""
    private var authCode: String = DEFAULT_AUTH_CODE
    private val history = mutableListOf<JSONObject>()
    private val autoTriggers = mutableListOf<String>()

    override val id: String = ID
    override val name: String = "Kill Switch"
    override val description: String =
        "Emergency stop mechanism. Commands: status, engage|reason, disengage|auth_code, " +
            "set_auth|code, check, history, auto_trigger|condition"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "status" -> handleStatus(start)
                "engage" -> handleEngage(parts, start)
                "disengage" -> handleDisengage(parts, start)
                "set_auth" -> handleSetAuth(parts, start)
                "check" -> handleCheck(start)
                "history" -> handleHistory(start)
                "auto_trigger" -> handleAutoTrigger(parts, start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: status, engage, disengage, set_auth, check, history, auto_trigger",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Kill switch error: ${e.message}", elapsed(start))
        }
    }

    private fun handleStatus(start: Long): PluginResult {
        val sb = buildString {
            append("Kill Switch Status:\n")
            append("  Engaged: $engaged\n")
            if (engaged) {
                append("  Reason: $engagedReason\n")
            }
            append("  Auth code set: ${authCode != DEFAULT_AUTH_CODE}\n")
            append("  History entries: ${history.size}\n")
            append("  Auto-trigger conditions: ${autoTriggers.size}")
            if (autoTriggers.isNotEmpty()) {
                append("\n  Conditions:\n")
                autoTriggers.forEachIndexed { i, condition ->
                    append("    ${i + 1}. $condition\n")
                }
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleEngage(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: engage|reason", elapsed(start))
        }
        if (engaged) {
            return PluginResult.success(
                "Kill switch already engaged. Reason: $engagedReason",
                elapsed(start)
            )
        }
        val reason = parts[1].trim()
        engaged = true
        engagedReason = reason
        addHistory("engaged", reason)
        save()
        return PluginResult.success(
            "KILL SWITCH ENGAGED. Reason: $reason. All plugin execution should halt.",
            elapsed(start)
        )
    }

    private fun handleDisengage(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: disengage|auth_code", elapsed(start))
        }
        if (!engaged) {
            return PluginResult.success("Kill switch is not engaged.", elapsed(start))
        }
        val providedCode = parts[1].trim()
        if (providedCode != authCode) {
            addHistory("disengage_failed", "Invalid auth code provided")
            save()
            return PluginResult.error(
                "Invalid auth code. Kill switch remains ENGAGED.",
                elapsed(start)
            )
        }
        val previousReason = engagedReason
        engaged = false
        engagedReason = ""
        addHistory("disengaged", "Previously engaged for: $previousReason")
        save()
        return PluginResult.success(
            "Kill switch DISENGAGED. System operations may resume.",
            elapsed(start)
        )
    }

    private fun handleSetAuth(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: set_auth|code", elapsed(start))
        }
        val newCode = parts[1].trim()
        if (newCode.length < 4) {
            return PluginResult.error(
                "Auth code must be at least 4 characters.",
                elapsed(start)
            )
        }
        authCode = newCode
        addHistory("auth_changed", "Auth code updated")
        save()
        return PluginResult.success("Auth code updated successfully.", elapsed(start))
    }

    private fun handleCheck(start: Long): PluginResult {
        val result = JSONObject().apply {
            put("engaged", engaged)
            if (engaged) {
                put("reason", engagedReason)
            }
        }
        return PluginResult.success(result.toString(), elapsed(start))
    }

    private fun handleHistory(start: Long): PluginResult {
        if (history.isEmpty()) {
            return PluginResult.success("No kill switch history.", elapsed(start))
        }
        val sb = buildString {
            append("Kill Switch History (${history.size} events):\n")
            history.forEachIndexed { i, event ->
                append("${i + 1}. [${formatTimestamp(event.optLong("timestamp"))}] ")
                append("${event.optString("event")}: ${event.optString("detail")}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleAutoTrigger(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: auto_trigger|condition", elapsed(start))
        }
        val condition = parts[1].trim()
        if (autoTriggers.contains(condition)) {
            return PluginResult.success(
                "Auto-trigger condition already registered: $condition",
                elapsed(start)
            )
        }
        autoTriggers.add(condition)
        addHistory("auto_trigger_added", condition)
        save()
        return PluginResult.success(
            "Auto-trigger condition registered: $condition (total: ${autoTriggers.size})",
            elapsed(start)
        )
    }

    private fun addHistory(event: String, detail: String) {
        history.add(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("event", event)
            put("detail", detail)
        })
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts == 0L) return "unknown"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ts))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun save() {
        val historyArr = JSONArray()
        history.forEach { historyArr.put(it) }
        val triggersArr = JSONArray()
        autoTriggers.forEach { triggersArr.put(it) }
        prefs.edit()
            .putBoolean(KEY_ENGAGED, engaged)
            .putString(KEY_REASON, engagedReason)
            .putString(KEY_AUTH_CODE, authCode)
            .putString(KEY_HISTORY, historyArr.toString())
            .putString(KEY_AUTO_TRIGGERS, triggersArr.toString())
            .apply()
    }

    private fun load() {
        engaged = prefs.getBoolean(KEY_ENGAGED, false)
        engagedReason = prefs.getString(KEY_REASON, "") ?: ""
        authCode = prefs.getString(KEY_AUTH_CODE, DEFAULT_AUTH_CODE) ?: DEFAULT_AUTH_CODE

        val historyData = prefs.getString(KEY_HISTORY, null)
        if (historyData != null) {
            try {
                val arr = JSONArray(historyData)
                history.clear()
                for (i in 0 until arr.length()) {
                    history.add(arr.getJSONObject(i))
                }
            } catch (_: Exception) { }
        }

        val triggersData = prefs.getString(KEY_AUTO_TRIGGERS, null)
        if (triggersData != null) {
            try {
                val arr = JSONArray(triggersData)
                autoTriggers.clear()
                for (i in 0 until arr.length()) {
                    autoTriggers.add(arr.getString(i))
                }
            } catch (_: Exception) { }
        }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    override fun destroy() {
        history.clear()
        autoTriggers.clear()
    }
}
