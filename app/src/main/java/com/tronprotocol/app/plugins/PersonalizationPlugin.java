package com.tronprotocol.app.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Map;

/**
 * User preference/profile memory plugin with explicit commands.
 */
public class PersonalizationPlugin implements Plugin {
    private static final String ID = "personalization";
    private static final String PREFS = "personalization_plugin";

    private SharedPreferences preferences;
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Personalization";
    }

    @Override
    public String getDescription() {
        return "Preference memory store. Commands: set|key|value, get|key, list, forget|key, export_json, clear";
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
                case "set":
                    if (parts.length < 3) return PluginResult.error("Usage: set|key|value", elapsed(start));
                    preferences.edit().putString(parts[1].trim(), parts[2].trim()).apply();
                    return PluginResult.success("Saved key: " + parts[1].trim(), elapsed(start));
                case "get":
                    if (parts.length < 2) return PluginResult.error("Usage: get|key", elapsed(start));
                    String value = preferences.getString(parts[1].trim(), null);
                    if (value == null) return PluginResult.error("Key not found: " + parts[1].trim(), elapsed(start));
                    return PluginResult.success(value, elapsed(start));
                case "list":
                    return PluginResult.success(preferences.getAll().keySet().toString(), elapsed(start));
                case "forget":
                    if (parts.length < 2) return PluginResult.error("Usage: forget|key", elapsed(start));
                    preferences.edit().remove(parts[1].trim()).apply();
                    return PluginResult.success("Removed key: " + parts[1].trim(), elapsed(start));
                case "export_json":
                    return PluginResult.success(exportJson(), elapsed(start));
                case "clear":
                    preferences.edit().clear().apply();
                    return PluginResult.success("Cleared personalization data", elapsed(start));
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Personalization failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String exportJson() {
        JSONObject json = new JSONObject();
        Map<String, ?> all = preferences.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            json.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return json.toString();
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
