package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
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

    override fun execute(request: PluginRequest): PluginResponse {
        val start = System.currentTimeMillis()
        return try {
            val command = request.command.trim().lowercase()
            if (command.isEmpty()) {
                return PluginResponse.error("No command provided", elapsed(start))
            }
            val legacy = if (request.rawInput.contains("|")) request.rawInput.split("|", limit = 3) else emptyList()
            when (command) {
                "add_channel" -> {
                    val name = request.args["name"] as? String ?: legacy.getOrNull(1)
                    val url = request.args["url"] as? String ?: legacy.getOrNull(2)
                    if (name.isNullOrBlank() || url.isNullOrBlank()) {
                        return PluginResponse.error("Usage: add_channel|name|url", elapsed(start))
                    }
                    PluginRequestValidator.requireAllowedUri(
                        url,
                        allowedSchemes = setOf("https"),
                        allowedHosts = emptySet()
                    )?.let { return PluginResponse.error(it, elapsed(start)) }
                    preferences.edit().putString(name.trim(), url.trim()).apply()
                    PluginResponse.success("Added channel: ${name.trim()}", elapsed(start))
                }
                "remove_channel" -> {
                    val name = request.args["name"] as? String ?: legacy.getOrNull(1)
                    if (name.isNullOrBlank()) return PluginResponse.error("Usage: remove_channel|name", elapsed(start))
                    preferences.edit().remove(name.trim()).apply()
                    PluginResponse.success("Removed channel: ${name.trim()}", elapsed(start))
                }
                "list_channels" -> PluginResponse.success(preferences.all.keys.toString(), elapsed(start))
                "send" -> {
                    val channelName = request.args["name"] as? String ?: legacy.getOrNull(1)
                    val message = request.args["message"] as? String ?: legacy.getOrNull(2)
                    if (channelName.isNullOrBlank() || message.isNullOrBlank()) {
                        return PluginResponse.error("Usage: send|name|message", elapsed(start))
                    }
                    PluginRequestValidator.enforceSizeLimit(message, 4096, "message")?.let {
                        return PluginResponse.error(it, elapsed(start))
                    }
                    sendToChannel(channelName.trim(), message, start)
                }
                else -> PluginResponse.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResponse.error("Communication hub failed: ${e.message}", elapsed(start))
        }
    }

    override fun execute(input: String): PluginResult {
        return execute(PluginRequest.fromLegacyInput(input)).toPluginResult()
    }

    private fun sendToChannel(channelName: String, message: String, start: Long): PluginResponse {
        val url = preferences.getString(channelName, null)
        if (TextUtils.isEmpty(url)) {
            return PluginResponse.error("Unknown channel: $channelName", elapsed(start))
        }

        val payload = JSONObject().apply {
            put("text", message)
            put("content", message)
        }

        val response = postJson(url!!, payload.toString())
        return PluginResponse.success("Sent to $channelName: $response", elapsed(start))
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
