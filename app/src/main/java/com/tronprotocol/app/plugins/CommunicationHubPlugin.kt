package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Communication hub plugin for webhook-based outbound messaging (Discord/Slack/custom).
 */
class CommunicationHubPlugin : Plugin {

    companion object {
        private const val ID = "communication_hub"
        private const val PREFS = "communication_hub_plugin"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Communication Hub"

    override val description: String =
        "Webhook communication channels. Commands: add_channel|name|url, remove_channel|name, list_channels, send|name|message"

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
                "add_channel" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: add_channel|name|url", elapsed(start))
                    preferences.edit().putString(parts[1].trim(), parts[2].trim()).apply()
                    PluginResult.success("Added channel: ${parts[1].trim()}", elapsed(start))
                }
                "remove_channel" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: remove_channel|name", elapsed(start))
                    preferences.edit().remove(parts[1].trim()).apply()
                    PluginResult.success("Removed channel: ${parts[1].trim()}", elapsed(start))
                }
                "list_channels" -> PluginResult.success(preferences.all.keys.toString(), elapsed(start))
                "send" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: send|name|message", elapsed(start))
                    sendToChannel(parts[1].trim(), parts[2].trim(), start)
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Communication hub failed: ${e.message}", elapsed(start))
        }
    }

    private fun sendToChannel(channelName: String, message: String, start: Long): PluginResult {
        val url = preferences.getString(channelName, null)
        if (TextUtils.isEmpty(url)) {
            return PluginResult.error("Unknown channel: $channelName", elapsed(start))
        }

        val payload = JSONObject().apply {
            put("text", message)
            put("content", message)
        }

        enforceOutboundGuardrails(url!!)
        val response = postJson(url, payload.toString())
        return PluginResult.success("Sent to $channelName: $response", elapsed(start))
    }

    private fun enforceOutboundGuardrails(endpoint: String) {
        val parsed = URL(endpoint)
        val host = parsed.host.lowercase()

        if (parsed.protocol.lowercase() != "https") {
            Log.w(ID, "Blocked outbound request with non-HTTPS protocol: ${parsed.protocol}")
            throw SecurityException("Outbound request blocked: HTTPS is required")
        }

        if (isBlockedHost(host)) {
            Log.w(ID, "Blocked outbound request to restricted host: $host")
            throw SecurityException("Outbound request blocked: restricted host")
        }
    }

    internal fun isBlockedHost(host: String): Boolean {
        val normalized = host.lowercase().trim()
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        if (normalized in setOf("0.0.0.0", "127.0.0.1")) return true
        if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) return true
        return normalized.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
    }

    private fun postJson(endpoint: String, body: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

        BufferedWriter(OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = connection.responseCode
        return if (code in 200 until 400) {
            readStream(connection)
        } else {
            "HTTP $code"
        }
    }

    private fun readStream(connection: HttpURLConnection): String {
        BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
            return sb.toString()
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}
