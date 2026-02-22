package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Granular Consent Plugin - Phase 8 Safety & Transparency.
 *
 * Manages fine-grained user consent for specific actions and data access.
 * Each consent record tracks the action name, granted/denied status,
 * timestamp, and the reason the consent was requested.
 *
 * Commands:
 *   request|action|reason   - Request consent for an action (defaults to pending)
 *   grant|action            - Grant consent for an action
 *   deny|action             - Deny consent for an action
 *   check|action            - Check whether consent exists and its status
 *   list                    - List all consent records
 *   revoke|action           - Revoke previously granted consent
 *   audit                   - Full consent history with timestamps
 */
class GranularConsentPlugin : Plugin {

    companion object {
        private const val ID = "granular_consent"
        private const val PREFS_NAME = "tronprotocol_granular_consent"
        private const val KEY_CONSENTS = "consent_records"
        private const val KEY_HISTORY = "consent_history"
    }

    private lateinit var prefs: SharedPreferences
    private val consents = mutableMapOf<String, JSONObject>()
    private val history = mutableListOf<JSONObject>()

    override val id: String = ID
    override val name: String = "Granular Consent"
    override val description: String =
        "Fine-grained consent management. Commands: request|action|reason, " +
            "grant|action, deny|action, check|action, list, revoke|action, audit"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "request" -> handleRequest(parts, start)
                "grant" -> handleGrant(parts, start)
                "deny" -> handleDeny(parts, start)
                "check" -> handleCheck(parts, start)
                "list" -> handleList(start)
                "revoke" -> handleRevoke(parts, start)
                "audit" -> handleAudit(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: request, grant, deny, check, list, revoke, audit",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Consent error: ${e.message}", elapsed(start))
        }
    }

    private fun handleRequest(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: request|action|reason", elapsed(start))
        }
        val action = parts[1].trim()
        val reason = parts[2].trim()
        val record = JSONObject().apply {
            put("action", action)
            put("status", "pending")
            put("reason", reason)
            put("requested_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        consents[action] = record
        addHistory(action, "requested", reason)
        save()
        return PluginResult.success(
            "Consent requested for '$action': $reason (status: pending)",
            elapsed(start)
        )
    }

    private fun handleGrant(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: grant|action", elapsed(start))
        }
        val action = parts[1].trim()
        val record = consents[action]
            ?: return PluginResult.error("No consent request found for '$action'. Use request first.", elapsed(start))
        record.put("status", "granted")
        record.put("updated_at", System.currentTimeMillis())
        addHistory(action, "granted", "User granted consent")
        save()
        return PluginResult.success("Consent GRANTED for '$action'.", elapsed(start))
    }

    private fun handleDeny(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: deny|action", elapsed(start))
        }
        val action = parts[1].trim()
        val record = consents[action]
            ?: return PluginResult.error("No consent request found for '$action'. Use request first.", elapsed(start))
        record.put("status", "denied")
        record.put("updated_at", System.currentTimeMillis())
        addHistory(action, "denied", "User denied consent")
        save()
        return PluginResult.success("Consent DENIED for '$action'.", elapsed(start))
    }

    private fun handleCheck(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: check|action", elapsed(start))
        }
        val action = parts[1].trim()
        val record = consents[action]
            ?: return PluginResult.success(
                "No consent record for '$action'. Consent not requested.",
                elapsed(start)
            )
        val status = record.optString("status", "unknown")
        val reason = record.optString("reason", "")
        val updatedAt = formatTimestamp(record.optLong("updated_at"))
        return PluginResult.success(
            "Consent for '$action': status=$status, reason=$reason, updated=$updatedAt",
            elapsed(start)
        )
    }

    private fun handleList(start: Long): PluginResult {
        if (consents.isEmpty()) {
            return PluginResult.success("No consent records.", elapsed(start))
        }
        val sb = buildString {
            append("Consent records (${consents.size}):\n")
            consents.entries.forEachIndexed { i, (action, record) ->
                val status = record.optString("status", "unknown")
                val reason = record.optString("reason", "")
                append("${i + 1}. $action: $status ($reason)\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleRevoke(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: revoke|action", elapsed(start))
        }
        val action = parts[1].trim()
        val record = consents[action]
            ?: return PluginResult.error("No consent record for '$action'.", elapsed(start))
        record.put("status", "revoked")
        record.put("updated_at", System.currentTimeMillis())
        addHistory(action, "revoked", "User revoked consent")
        save()
        return PluginResult.success("Consent REVOKED for '$action'.", elapsed(start))
    }

    private fun handleAudit(start: Long): PluginResult {
        if (history.isEmpty()) {
            return PluginResult.success("No consent history.", elapsed(start))
        }
        val sb = buildString {
            append("Consent history (${history.size} events):\n")
            history.forEachIndexed { i, event ->
                append("${i + 1}. [${formatTimestamp(event.optLong("timestamp"))}] ")
                append("${event.optString("action")}: ${event.optString("event")} ")
                append("- ${event.optString("detail")}\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun addHistory(action: String, event: String, detail: String) {
        history.add(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("action", action)
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
        val consentsObj = JSONObject()
        consents.forEach { (key, value) -> consentsObj.put(key, value) }
        val historyArr = JSONArray()
        history.forEach { historyArr.put(it) }
        prefs.edit()
            .putString(KEY_CONSENTS, consentsObj.toString())
            .putString(KEY_HISTORY, historyArr.toString())
            .apply()
    }

    private fun load() {
        val consentsData = prefs.getString(KEY_CONSENTS, null)
        if (consentsData != null) {
            try {
                val obj = JSONObject(consentsData)
                consents.clear()
                obj.keys().forEach { key ->
                    consents[key] = obj.getJSONObject(key)
                }
            } catch (_: Exception) { }
        }
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
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    override fun destroy() {
        consents.clear()
        history.clear()
    }
}
