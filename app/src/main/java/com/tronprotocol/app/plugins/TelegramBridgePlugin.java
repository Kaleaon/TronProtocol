package com.tronprotocol.app.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Telegram bridge plugin.
 *
 * Configure this plugin with a BotFather token, then allow specific Telegram chat IDs.
 * Authorized chats can send messages that the app can read via fetch command.
 */
public class TelegramBridgePlugin implements Plugin {
    private static final String ID = "telegram_bridge";
    private static final String PREFS = "telegram_bridge_plugin";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_ALLOWED_CHATS = "allowed_chats";
    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";

    private boolean enabled = true;
    private SharedPreferences preferences;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Telegram Bridge";
    }

    @Override
    public String getDescription() {
        return "Bridge the app through a Telegram bot. Commands: set_token|token, allow_chat|chatId, " +
            "deny_chat|chatId, list_allowed, fetch|offset, reply|chatId|text";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PluginResult execute(String input) {
        long start = System.currentTimeMillis();
        try {
            if (input == null || input.trim().isEmpty()) {
                return PluginResult.error("No command provided", elapsed(start));
            }

            String[] parts = input.split("\\|", 3);
            String command = parts[0].trim().toLowerCase();

            switch (command) {
                case "set_token":
                    return setToken(parts, start);
                case "allow_chat":
                    return allowChat(parts, start);
                case "deny_chat":
                    return denyChat(parts, start);
                case "list_allowed":
                    return listAllowedChats(start);
                case "fetch":
                    return fetchMessages(parts, start);
                case "reply":
                    return replyToChat(parts, start);
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Telegram bridge failed: " + e.getMessage(), elapsed(start));
        }
    }

    private PluginResult setToken(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: set_token|<bot_token>", elapsed(start));
        }

        preferences.edit().putString(KEY_BOT_TOKEN, parts[1].trim()).apply();
        return PluginResult.success("Telegram bot token saved", elapsed(start));
    }

    private PluginResult allowChat(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: allow_chat|<chat_id>", elapsed(start));
        }

        Set<String> allowed = getAllowedChats();
        allowed.add(parts[1].trim());
        preferences.edit().putStringSet(KEY_ALLOWED_CHATS, allowed).apply();
        return PluginResult.success("Allowed chat id: " + parts[1].trim(), elapsed(start));
    }

    private PluginResult denyChat(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: deny_chat|<chat_id>", elapsed(start));
        }

        Set<String> allowed = getAllowedChats();
        allowed.remove(parts[1].trim());
        preferences.edit().putStringSet(KEY_ALLOWED_CHATS, allowed).apply();
        return PluginResult.success("Denied chat id: " + parts[1].trim(), elapsed(start));
    }

    private PluginResult listAllowedChats(long start) {
        Set<String> allowed = getAllowedChats();
        if (allowed.isEmpty()) {
            return PluginResult.success("No allowed chats configured", elapsed(start));
        }

        StringBuilder result = new StringBuilder("Allowed chats:\n");
        for (String id : allowed) {
            result.append("- ").append(id).append("\n");
        }

        return PluginResult.success(result.toString(), elapsed(start));
    }

    private PluginResult fetchMessages(String[] parts, long start) throws Exception {
        String token = getToken();
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start));
        }

        int offset = 0;
        if (parts.length >= 2 && !TextUtils.isEmpty(parts[1].trim())) {
            offset = Integer.parseInt(parts[1].trim());
        }

        String response = get("%s/getUpdates?offset=%d&timeout=1", token, offset);
        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("ok", false)) {
            return PluginResult.error("Telegram getUpdates returned not ok", elapsed(start));
        }

        JSONArray results = json.optJSONArray("result");
        Set<String> allowed = getAllowedChats();
        StringBuilder out = new StringBuilder();
        int lastUpdateId = offset;
        int accepted = 0;

        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject update = results.getJSONObject(i);
                lastUpdateId = Math.max(lastUpdateId, update.optInt("update_id", lastUpdateId));
                JSONObject message = update.optJSONObject("message");
                if (message == null) {
                    continue;
                }

                JSONObject chat = message.optJSONObject("chat");
                String chatId = chat != null ? String.valueOf(chat.optLong("id")) : "";
                if (!allowed.contains(chatId)) {
                    continue;
                }

                String text = message.optString("text", "");
                String username = "unknown";
                JSONObject from = message.optJSONObject("from");
                if (from != null) {
                    username = from.optString("username", from.optString("first_name", "unknown"));
                }

                out.append("[").append(chatId).append("] ").append(username)
                    .append(": ").append(text).append("\n");
                accepted++;
            }
        }

        if (accepted == 0) {
            out.append("No authorized messages. Next offset: ").append(lastUpdateId + 1);
        } else {
            out.append("Next offset: ").append(lastUpdateId + 1);
        }

        return PluginResult.success(out.toString(), elapsed(start));
    }

    private PluginResult replyToChat(String[] parts, long start) throws Exception {
        String token = getToken();
        if (TextUtils.isEmpty(token)) {
            return PluginResult.error("Bot token missing. Use set_token first", elapsed(start));
        }

        if (parts.length < 3 || TextUtils.isEmpty(parts[1].trim()) || TextUtils.isEmpty(parts[2].trim())) {
            return PluginResult.error("Usage: reply|<chat_id>|<message>", elapsed(start));
        }

        String chatId = parts[1].trim();
        if (!getAllowedChats().contains(chatId)) {
            return PluginResult.error("Chat is not authorized: " + chatId, elapsed(start));
        }

        String payload = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
            + "&text=" + URLEncoder.encode(parts[2].trim(), "UTF-8");

        String response = post("%s/sendMessage", token, payload);
        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("ok", false)) {
            return PluginResult.error("Telegram sendMessage returned not ok", elapsed(start));
        }

        return PluginResult.success("Sent message to chat " + chatId, elapsed(start));
    }

    private String get(String format, String token, int offset) throws Exception {
        URL url = new URL(String.format(format, TELEGRAM_API_BASE + token, offset));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String post(String format, String token, String body) throws Exception {
        URL url = new URL(String.format(format, TELEGRAM_API_BASE + token));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(body);
            writer.flush();
        }

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String getToken() {
        return preferences.getString(KEY_BOT_TOKEN, "");
    }

    private Set<String> getAllowedChats() {
        return new HashSet<>(preferences.getStringSet(KEY_ALLOWED_CHATS, new HashSet<String>()));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    @Override
    public void initialize(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void destroy() {
        // No-op
    }
}
