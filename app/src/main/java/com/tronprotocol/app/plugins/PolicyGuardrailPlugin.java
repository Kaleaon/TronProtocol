package com.tronprotocol.app.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Central policy guardrail plugin used by PluginManager before plugin execution.
 */
public class PolicyGuardrailPlugin implements Plugin {
    private static final String ID = "policy_guardrail";
    private static final String PREFS = "policy_guardrail_plugin";
    private static final String KEY_DENIED = "denied_plugins";
    private static final String KEY_BLOCKED_PATTERNS = "blocked_patterns";

    private SharedPreferences preferences;
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Policy Guardrail";
    }

    @Override
    public String getDescription() {
        return "Policy gate for plugin execution. Commands: deny_plugin|id, allow_plugin|id, list_denied, " +
            "add_pattern|text, remove_pattern|text, list_patterns, check|pluginId|input";
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
                case "deny_plugin":
                    return denyPlugin(parts, start);
                case "allow_plugin":
                    return allowPlugin(parts, start);
                case "list_denied":
                    return PluginResult.success("Denied plugins: " + getDeniedPlugins(), elapsed(start));
                case "add_pattern":
                    return addPattern(parts, start);
                case "remove_pattern":
                    return removePattern(parts, start);
                case "list_patterns":
                    return PluginResult.success("Blocked patterns: " + getBlockedPatterns(), elapsed(start));
                case "check":
                    return check(parts, start);
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Policy command failed: " + e.getMessage(), elapsed(start));
        }
    }

    public PluginResult evaluate(String pluginId, String input) {
        long start = System.currentTimeMillis();
        if (TextUtils.isEmpty(pluginId)) {
            return PluginResult.error("Plugin ID missing", elapsed(start));
        }

        if (getDeniedPlugins().contains(pluginId)) {
            return PluginResult.error("Policy blocked plugin: " + pluginId, elapsed(start));
        }

        if (!TextUtils.isEmpty(input)) {
            String lowered = input.toLowerCase();
            for (String pattern : getBlockedPatterns()) {
                if (!TextUtils.isEmpty(pattern) && lowered.contains(pattern.toLowerCase())) {
                    return PluginResult.error("Policy blocked input pattern: " + pattern, elapsed(start));
                }
            }
        }

        return PluginResult.success("Allowed", elapsed(start));
    }

    private PluginResult check(String[] parts, long start) {
        if (parts.length < 2) {
            return PluginResult.error("Usage: check|<plugin_id>|<input_optional>", elapsed(start));
        }
        String pluginId = parts[1].trim();
        String payload = parts.length >= 3 ? parts[2] : "";
        return evaluate(pluginId, payload);
    }

    private PluginResult denyPlugin(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: deny_plugin|<plugin_id>", elapsed(start));
        }
        Set<String> denied = getDeniedPlugins();
        denied.add(parts[1].trim());
        preferences.edit().putStringSet(KEY_DENIED, denied).apply();
        return PluginResult.success("Denied plugin: " + parts[1].trim(), elapsed(start));
    }

    private PluginResult allowPlugin(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: allow_plugin|<plugin_id>", elapsed(start));
        }
        Set<String> denied = getDeniedPlugins();
        denied.remove(parts[1].trim());
        preferences.edit().putStringSet(KEY_DENIED, denied).apply();
        return PluginResult.success("Allowed plugin: " + parts[1].trim(), elapsed(start));
    }

    private PluginResult addPattern(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: add_pattern|<text>", elapsed(start));
        }
        Set<String> patterns = getBlockedPatterns();
        patterns.add(parts[1].trim());
        preferences.edit().putStringSet(KEY_BLOCKED_PATTERNS, patterns).apply();
        return PluginResult.success("Added blocked pattern", elapsed(start));
    }

    private PluginResult removePattern(String[] parts, long start) {
        if (parts.length < 2 || TextUtils.isEmpty(parts[1].trim())) {
            return PluginResult.error("Usage: remove_pattern|<text>", elapsed(start));
        }
        Set<String> patterns = getBlockedPatterns();
        patterns.remove(parts[1].trim());
        preferences.edit().putStringSet(KEY_BLOCKED_PATTERNS, patterns).apply();
        return PluginResult.success("Removed blocked pattern", elapsed(start));
    }

    private Set<String> getDeniedPlugins() {
        return new HashSet<>(preferences.getStringSet(KEY_DENIED, new HashSet<String>()));
    }

    private Set<String> getBlockedPatterns() {
        Set<String> defaults = new HashSet<>(Arrays.asList("rm -rf", "drop table", "format /", "shutdown"));
        return new HashSet<>(preferences.getStringSet(KEY_BLOCKED_PATTERNS, defaults));
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
