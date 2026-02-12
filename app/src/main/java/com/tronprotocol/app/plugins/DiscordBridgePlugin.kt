package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Discord Bridge Plugin — bidirectional messaging via Discord Bot API.
 *
 * Modeled on TelegramBridgePlugin's pattern, adapted for Discord's REST API.
 * Can also delegate to PicoClaw edge nodes running `picoclaw gateway` for
 * persistent WebSocket connections (Discord Gateway requires persistent
 * connections that are impractical on mobile).
 *
 * Commands:
 *   set_token|bot_token           — Store Discord bot token
 *   allow_channel|channelId       — Whitelist a Discord channel
 *   deny_channel|channelId        — Remove a channel from whitelist
 *   list_allowed                  — List allowed channels
 *   fetch|channelId[|limit]       — Fetch recent messages from a channel
 *   send|channelId|message        — Send a message to a Discord channel
 *   delegate|nodeId               — Delegate gateway to a PicoClaw edge node
 */
class DiscordBridgePlugin : Plugin {

    companion object {
        private const val TAG = "DiscordBridge"
        private const val ID = "discord_bridge"
        private const val PREFS = "discord_bridge_plugin"
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_ALLOWED_CHANNELS = "allowed_channels"
        private const val KEY_DELEGATE_NODE = "delegate_node_id"
        private const val DISCORD_API_BASE = "https://discord.com/api/v10"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID
    override val name: String = "Discord Bridge"
    override val description: String =
        "Bridge the app through a Discord bot. " +
            "Commands: set_token|token, allow_channel|id, deny_channel|id, " +
            "list_allowed, fetch|channelId[|limit], send|channelId|msg, delegate|nodeId"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isNullOrBlank()) {
                return PluginResult.error("No command provided", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set_token" -> setToken(parts, start)
                "allow_channel" -> allowChannel(parts, start)
                "deny_channel" -> denyChannel(parts, start)
                "list_allowed" -> listAllowedChannels(start)
                "fetch" -> fetchMessages(parts, start)
                "send" -> sendMessage(parts, start)
                "delegate" -> delegateToNode(parts, start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Discord bridge failed: ${e.message}", elapsed(start))
        }
    }

    private fun setToken(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: set_token|<bot_token>", elapsed(start))
        }
        preferences.edit().putString(KEY_BOT_TOKEN, parts[1].trim()).apply()
        return PluginResult.success("Discord bot token saved", elapsed(start))
    }

    private fun allowChannel(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: allow_channel|<channel_id>", elapsed(start))
        }
        val allowed = getAllowedChannels()
        allowed.add(parts[1].trim())
        preferences.edit().putStringSet(KEY_ALLOWED_CHANNELS, allowed).apply()
        return PluginResult.success("Allowed channel: ${parts[1].trim()}", elapsed(start))
    }

    private fun denyChannel(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: deny_channel|<channel_id>", elapsed(start))
        }
        val allowed = getAllowedChannels()
        allowed.remove(parts[1].trim())
        preferences.edit().putStringSet(KEY_ALLOWED_CHANNELS, allowed).apply()
        return PluginResult.success("Denied channel: ${parts[1].trim()}", elapsed(start))
    }

    private fun listAllowedChannels(start: Long): PluginResult {
        val allowed = getAllowedChannels()
        if (allowed.isEmpty()) {
            return PluginResult.success("No allowed channels configured", elapsed(start))
        }
        val result = buildString {
            append("Allowed Discord channels:\n")
            for (id in allowed) append("- $id\n")
        }
        return PluginResult.success(result, elapsed(start))
    }

    private fun fetchMessages(parts: List<String>, start: Long): PluginResult {
        val token = getToken()
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start))
        }

        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: fetch|channelId[|limit]", elapsed(start))
        }

        val channelId = parts[1].trim()
        if (!getAllowedChannels().contains(channelId)) {
            return PluginResult.error("Channel not authorized: $channelId", elapsed(start))
        }

        val limit = if (parts.size >= 3) {
            parts[2].trim().toIntOrNull()?.coerceIn(1, 100) ?: 10
        } else 10

        val response = discordGet("/channels/$channelId/messages?limit=$limit", token!!)
        val messages = JSONArray(response)
        val out = StringBuilder()
        var count = 0

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val author = msg.optJSONObject("author")?.optString("username", "unknown") ?: "unknown"
            val content = msg.optString("content", "")
            val timestamp = msg.optString("timestamp", "")

            out.append("[$timestamp] $author: $content\n")
            count++
        }

        if (count == 0) {
            out.append("No messages in channel $channelId")
        }

        return PluginResult.success(out.toString().trimEnd(), elapsed(start))
    }

    private fun sendMessage(parts: List<String>, start: Long): PluginResult {
        val token = getToken()
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start))
        }

        if (parts.size < 3 || TextUtils.isEmpty(parts[1].trim()) || TextUtils.isEmpty(parts[2].trim())) {
            return PluginResult.error("Usage: send|channelId|message", elapsed(start))
        }

        val channelId = parts[1].trim()
        if (!getAllowedChannels().contains(channelId)) {
            return PluginResult.error("Channel not authorized: $channelId", elapsed(start))
        }

        val payload = JSONObject().apply {
            put("content", parts[2].trim())
        }

        discordPost("/channels/$channelId/messages", token!!, payload.toString())
        return PluginResult.success("Message sent to Discord channel $channelId", elapsed(start))
    }

    private fun delegateToNode(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: delegate|nodeId", elapsed(start))
        }
        val nodeId = parts[1].trim()
        preferences.edit().putString(KEY_DELEGATE_NODE, nodeId).apply()
        return PluginResult.success(
            "Discord gateway delegated to PicoClaw node '$nodeId'. " +
                "The node will maintain persistent WebSocket connection.", elapsed(start)
        )
    }

    // ========================================================================
    // Discord REST API helpers
    // ========================================================================

    private fun discordGet(path: String, token: String): String {
        val url = URL("$DISCORD_API_BASE$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bot $token")
        connection.setRequestProperty("Content-Type", "application/json")

        try {
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                return sb.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun discordPost(path: String, token: String, body: String): String {
        val url = URL("$DISCORD_API_BASE$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bot $token")
        connection.setRequestProperty("Content-Type", "application/json")

        BufferedWriter(OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = connection.responseCode
        try {
            val stream = if (code in 200 until 400) connection.inputStream else connection.errorStream
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                if (code >= 400) throw RuntimeException("Discord API error $code: ${sb.toString()}")
                return sb.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun getToken(): String? = preferences.getString(KEY_BOT_TOKEN, null)

    private fun getAllowedChannels(): MutableSet<String> =
        HashSet(preferences.getStringSet(KEY_ALLOWED_CHANNELS, HashSet<String>()) ?: HashSet())

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Log.d(TAG, "DiscordBridgePlugin initialized")
    }

    override fun destroy() {
        // No-op
    }
}
