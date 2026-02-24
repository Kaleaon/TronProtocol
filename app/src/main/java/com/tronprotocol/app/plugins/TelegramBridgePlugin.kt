package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Telegram bridge plugin.
 *
 * Configure this plugin with a BotFather token, then allow specific Telegram chat IDs.
 * Authorized chats can send messages that the app can read via fetch command.
 */
class TelegramBridgePlugin : Plugin {

    companion object {
        private const val ID = "telegram_bridge"
        private const val PREFS = "telegram_bridge_plugin"
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_ALLOWED_CHATS = "allowed_chats"
        private const val TELEGRAM_API_BASE = "https://api.telegram.org/bot"
        private val BOT_TOKEN_REGEX = Regex("\\b\\d{6,}:[A-Za-z0-9_-]{20,}\\b")
        private val CHAT_ID_PARAM_REGEX = Regex("chat_id\\s*[=:]\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
        private val TELEGRAM_PRIVATE_CHAT_LINK_REGEX = Regex("https?://t\\.me/c/(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Telegram Bridge"

    override val description: String =
        "Bridge the app through a Telegram bot. Commands: set_token|token, allow_chat|chatId, " +
            "deny_chat|chatId, list_allowed, fetch|offset, reply|chatId|text, import_shared|text"

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
                "allow_chat" -> allowChat(parts, start)
                "deny_chat" -> denyChat(parts, start)
                "list_allowed" -> listAllowedChats(start)
                "fetch" -> fetchMessages(parts, start)
                "reply" -> replyToChat(parts, start)
                "import_shared" -> importShared(parts, start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Telegram bridge failed: ${e.message}", elapsed(start))
        }
    }

    private fun setToken(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: set_token|<bot_token>", elapsed(start))
        }
        preferences.edit().putString(KEY_BOT_TOKEN, parts[1].trim()).apply()
        return PluginResult.success("Telegram bot token saved", elapsed(start))
    }

    private fun allowChat(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: allow_chat|<chat_id>", elapsed(start))
        }
        val allowed = getAllowedChats()
        allowed.add(parts[1].trim())
        preferences.edit().putStringSet(KEY_ALLOWED_CHATS, allowed).apply()
        return PluginResult.success("Allowed chat id: ${parts[1].trim()}", elapsed(start))
    }

    private fun denyChat(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: deny_chat|<chat_id>", elapsed(start))
        }
        val allowed = getAllowedChats()
        allowed.remove(parts[1].trim())
        preferences.edit().putStringSet(KEY_ALLOWED_CHATS, allowed).apply()
        return PluginResult.success("Denied chat id: ${parts[1].trim()}", elapsed(start))
    }

    private fun listAllowedChats(start: Long): PluginResult {
        val allowed = getAllowedChats()
        if (allowed.isEmpty()) {
            return PluginResult.success("No allowed chats configured", elapsed(start))
        }

        val result = buildString {
            append("Allowed chats:\n")
            for (id in allowed) {
                append("- $id\n")
            }
        }
        return PluginResult.success(result, elapsed(start))
    }

    private fun fetchMessages(parts: List<String>, start: Long): PluginResult {
        val token = getToken()
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start))
        }

        var offset = 0
        if (parts.size >= 2 && !TextUtils.isEmpty(parts[1].trim())) {
            offset = parts[1].trim().toInt()
        }

        val response = get("%s/getUpdates?offset=%d&timeout=1", token, offset)
        val json = JSONObject(response)
        if (!json.optBoolean("ok", false)) {
            return PluginResult.error("Telegram getUpdates returned not ok", elapsed(start))
        }

        val results: JSONArray? = json.optJSONArray("result")
        val allowed = getAllowedChats()
        val out = StringBuilder()
        var lastUpdateId = offset
        var accepted = 0

        if (results != null) {
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                lastUpdateId = maxOf(lastUpdateId, update.optInt("update_id", lastUpdateId))
                val message = update.optJSONObject("message") ?: continue

                val chat = message.optJSONObject("chat")
                val chatId = if (chat != null) chat.optLong("id").toString() else ""
                if (!allowed.contains(chatId)) continue

                val text = message.optString("text", "")
                val from = message.optJSONObject("from")
                val username = from?.optString("username", from.optString("first_name", "unknown"))
                    ?: "unknown"

                out.append("[$chatId] $username: $text\n")
                accepted++
            }
        }

        if (accepted == 0) {
            out.append("No authorized messages. Next offset: ${lastUpdateId + 1}")
        } else {
            out.append("Next offset: ${lastUpdateId + 1}")
        }

        return PluginResult.success(out.toString(), elapsed(start))
    }

    private fun replyToChat(parts: List<String>, start: Long): PluginResult {
        val token = getToken()
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start))
        }

        if (parts.size < 3 || TextUtils.isEmpty(parts[1].trim()) || TextUtils.isEmpty(parts[2].trim())) {
            return PluginResult.error("Usage: reply|<chat_id>|<message>", elapsed(start))
        }

        val chatId = parts[1].trim()
        if (!getAllowedChats().contains(chatId)) {
            return PluginResult.error("Chat is not authorized: $chatId", elapsed(start))
        }

        val payload = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}" +
            "&text=${URLEncoder.encode(parts[2].trim(), "UTF-8")}"

        val response = post("%s/sendMessage", token, payload)
        val json = JSONObject(response)
        if (!json.optBoolean("ok", false)) {
            return PluginResult.error("Telegram sendMessage returned not ok", elapsed(start))
        }

        return PluginResult.success("Sent message to chat $chatId", elapsed(start))
    }

    private fun importShared(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: import_shared|<text>", elapsed(start))
        }

        val sharedText = parts.drop(1).joinToString("|").trim()
        val messages = mutableListOf<String>()
        var token = getToken()

        val extractedToken = BOT_TOKEN_REGEX.find(sharedText)?.value
        if (!TextUtils.isEmpty(extractedToken)) {
            token = extractedToken ?: ""
            preferences.edit().putString(KEY_BOT_TOKEN, token).apply()
            messages.add("Bot token saved from shared text")
        }

        var chatId = extractChatId(sharedText)
        if (TextUtils.isEmpty(chatId) && !TextUtils.isEmpty(token)) {
            chatId = discoverLatestChatId(token)
        }

        if (!TextUtils.isEmpty(chatId)) {
            val allowed = getAllowedChats()
            val added = allowed.add(chatId ?: "")
            preferences.edit().putStringSet(KEY_ALLOWED_CHATS, allowed).apply()
            if (added) {
                messages.add("Allowed chat id: $chatId")
            } else {
                messages.add("Chat id already allowed: $chatId")
            }
        } else if (!TextUtils.isEmpty(token)) {
            messages.add("No chat found yet. Send a message to the bot in Telegram, then share any bot message to this app.")
        }

        if (messages.isEmpty()) {
            return PluginResult.error("No Telegram token or chat information found in shared text", elapsed(start))
        }
        return PluginResult.success(messages.joinToString(". "), elapsed(start))
    }

    private fun extractChatId(sharedText: String): String? {
        val chatIdFromParam = CHAT_ID_PARAM_REGEX.find(sharedText)?.groupValues?.getOrNull(1)
        if (!TextUtils.isEmpty(chatIdFromParam)) return chatIdFromParam

        val privateChatLink = TELEGRAM_PRIVATE_CHAT_LINK_REGEX.find(sharedText)?.groupValues?.getOrNull(1)
        if (!TextUtils.isEmpty(privateChatLink)) return "-100$privateChatLink"

        return null
    }

    private fun discoverLatestChatId(token: String): String? {
        return try {
            val response = get("%s/getUpdates?offset=%d&timeout=1", token, 0)
            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) return null
            val results = json.optJSONArray("result") ?: return null
            for (i in results.length() - 1 downTo 0) {
                val update = results.optJSONObject(i) ?: continue
                val message = update.optJSONObject("message") ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val chatId = chat.optLong("id", Long.MIN_VALUE)
                if (chatId != Long.MIN_VALUE) {
                    return chatId.toString()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun get(format: String, token: String, offset: Int): String {
        val url = URL(String.format(format, TELEGRAM_API_BASE + token, offset))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"

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

    private fun post(format: String, token: String, body: String): String {
        val url = URL(String.format(format, TELEGRAM_API_BASE + token))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        BufferedWriter(OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }

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

    private fun getToken(): String = preferences.getString(KEY_BOT_TOKEN, "") ?: ""

    private fun getAllowedChats(): MutableSet<String> =
        HashSet(preferences.getStringSet(KEY_ALLOWED_CHATS, HashSet<String>()) ?: HashSet())

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }

}
