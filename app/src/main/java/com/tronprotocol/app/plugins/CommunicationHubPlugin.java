package com.tronprotocol.app.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Communication hub plugin for webhook-based outbound messaging (Discord/Slack/custom).
 */
public class CommunicationHubPlugin implements Plugin {
    private static final String ID = "communication_hub";
    private static final String PREFS = "communication_hub_plugin";

    private SharedPreferences preferences;
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Communication Hub";
    }

    @Override
    public String getDescription() {
        return "Webhook communication channels. Commands: add_channel|name|url, remove_channel|name, list_channels, send|name|message";
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
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start));
            }
            String[] parts = input.split("\\|", 3);
            String command = parts[0].trim().toLowerCase();
            switch (command) {
                case "add_channel":
                    if (parts.length < 3) return PluginResult.error("Usage: add_channel|name|url", elapsed(start));
                    preferences.edit().putString(parts[1].trim(), parts[2].trim()).apply();
                    return PluginResult.success("Added channel: " + parts[1].trim(), elapsed(start));
                case "remove_channel":
                    if (parts.length < 2) return PluginResult.error("Usage: remove_channel|name", elapsed(start));
                    preferences.edit().remove(parts[1].trim()).apply();
                    return PluginResult.success("Removed channel: " + parts[1].trim(), elapsed(start));
                case "list_channels":
                    return PluginResult.success(preferences.getAll().keySet().toString(), elapsed(start));
                case "send":
                    if (parts.length < 3) return PluginResult.error("Usage: send|name|message", elapsed(start));
                    return sendToChannel(parts[1].trim(), parts[2].trim(), start);
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Communication hub failed: " + e.getMessage(), elapsed(start));
        }
    }

    private PluginResult sendToChannel(String channelName, String message, long start) throws Exception {
        String url = preferences.getString(channelName, null);
        if (TextUtils.isEmpty(url)) {
            return PluginResult.error("Unknown channel: " + channelName, elapsed(start));
        }

        JSONObject payload = new JSONObject();
        payload.put("text", message);
        payload.put("content", message);

        String response = postJson(url, payload.toString());
        return PluginResult.success("Sent to " + channelName + ": " + response, elapsed(start));
    }

    private String postJson(String endpoint, String body) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(body);
            writer.flush();
        }

        int code = connection.getResponseCode();
        if (code >= 200 && code < 400) {
            return readStream(connection);
        }
        return "HTTP " + code;
    }

    private String readStream(HttpURLConnection connection) throws Exception {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
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
