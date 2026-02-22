package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Actual SMS sending with rate limiting and configurable allow lists.
 *
 * Commands:
 *   send|number|message    – Send SMS to allowed number
 *   allow|number           – Add number to allow list
 *   deny|number            – Remove number from allow list
 *   list_allowed           – Show allowed numbers
 *   history|count          – Recent sent SMS log
 *   rate_status            – Current rate limit status
 */
class SMSSendPlugin : Plugin {

    override val id: String = ID
    override val name: String = "SMS Send"
    override val description: String =
        "Send SMS messages. Commands: send|number|message, allow|number, deny|number, list_allowed, history|count, rate_status"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences
    private var sentThisHour = 0
    private var hourStart = 0L

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "send" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: send|number|message", elapsed(start))
                    val number = parts[1].trim()
                    val message = parts[2].trim()

                    if (!isAllowed(number)) {
                        return PluginResult.error("Number not in allow list: $number. Use allow|$number first.", elapsed(start))
                    }
                    if (!checkRateLimit()) {
                        return PluginResult.error("Rate limit exceeded ($MAX_PER_HOUR SMS/hour). Try again later.", elapsed(start))
                    }
                    if (message.length > 1600) {
                        return PluginResult.error("Message too long (max 1600 chars)", elapsed(start))
                    }

                    @Suppress("DEPRECATION")
                    val smsManager = SmsManager.getDefault()
                    val messageParts = smsManager.divideMessage(message)
                    smsManager.sendMultipartTextMessage(number, null, messageParts, null, null)

                    sentThisHour++
                    recordSent(number, message)
                    PluginResult.success("SMS sent to $number (${messageParts.size} parts)", elapsed(start))
                }
                "allow" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: allow|number", elapsed(start))
                    val allowed = getAllowed().toMutableSet()
                    allowed.add(parts[1].trim())
                    prefs.edit().putStringSet("allowed_numbers", allowed).apply()
                    PluginResult.success("Added to allow list: ${parts[1].trim()}", elapsed(start))
                }
                "deny" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: deny|number", elapsed(start))
                    val allowed = getAllowed().toMutableSet()
                    allowed.remove(parts[1].trim())
                    prefs.edit().putStringSet("allowed_numbers", allowed).apply()
                    PluginResult.success("Removed from allow list: ${parts[1].trim()}", elapsed(start))
                }
                "list_allowed" -> {
                    val allowed = getAllowed()
                    PluginResult.success("Allowed numbers (${allowed.size}): ${allowed.joinToString(", ")}", elapsed(start))
                }
                "history" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    val history = loadHistory()
                    val recent = JSONArray()
                    val startIdx = (history.length() - count).coerceAtLeast(0)
                    for (i in startIdx until history.length()) {
                        recent.put(history.getJSONObject(i))
                    }
                    PluginResult.success("SMS history (${recent.length()}):\n${recent.toString(2)}", elapsed(start))
                }
                "rate_status" -> {
                    resetRateIfNeeded()
                    PluginResult.success("Sent this hour: $sentThisHour/$MAX_PER_HOUR", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("SMS permission not granted", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("SMS error: ${e.message}", elapsed(start))
        }
    }

    private fun isAllowed(number: String): Boolean = getAllowed().contains(number)

    private fun getAllowed(): Set<String> = prefs.getStringSet("allowed_numbers", emptySet()) ?: emptySet()

    private fun checkRateLimit(): Boolean {
        resetRateIfNeeded()
        return sentThisHour < MAX_PER_HOUR
    }

    private fun resetRateIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - hourStart > 3600000) {
            sentThisHour = 0
            hourStart = now
        }
    }

    private fun recordSent(number: String, message: String) {
        val history = loadHistory()
        history.put(JSONObject().apply {
            put("number", number)
            put("message", message.take(200))
            put("timestamp", System.currentTimeMillis())
        })
        // Keep last 200
        while (history.length() > 200) history.remove(0)
        prefs.edit().putString("sms_history", history.toString()).apply()
    }

    private fun loadHistory(): JSONArray {
        val str = prefs.getString("sms_history", null) ?: return JSONArray()
        return try { JSONArray(str) } catch (_: Exception) { JSONArray() }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("sms_send_plugin", Context.MODE_PRIVATE)
        hourStart = System.currentTimeMillis()
    }

    override fun destroy() {}

    companion object {
        const val ID = "sms_send"
        private const val MAX_PER_HOUR = 10
    }
}
