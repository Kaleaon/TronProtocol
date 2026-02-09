package com.tronprotocol.app.plugins;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages all plugins in the TronProtocol system
 * 
 * Inspired by ToolNeuron's plugin management architecture
 */
public class PluginManager {
    private static final String TAG = "PluginManager";
    private static PluginManager instance;
    
    private final Map<String, Plugin> plugins;
    private Context context;
    
    private PluginManager() {
        plugins = new HashMap<>();
    }
    
    public static synchronized PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager();
        }
        return instance;
    }
    
    public void initialize(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "PluginManager initialized");
    }
    
    /**
     * Register a plugin
     */
    public boolean registerPlugin(Plugin plugin) {
        if (plugin == null) {
            Log.w(TAG, "Skipping plugin registration: plugin is null");
            return false;
        }

        if (context == null) {
            Log.w(TAG, "Skipping plugin registration for " + plugin.getClass().getName() + ": context is null");
            return false;
        }

        try {
            plugin.initialize(context);
            plugins.put(plugin.getId(), plugin);
            Log.d(TAG, "Registered plugin: " + plugin.getName());
            return true;
        } catch (Exception e) {
            String pluginId;
            try {
                pluginId = plugin.getId();
            } catch (Exception idException) {
                pluginId = "<unavailable>";
            }

            Log.e(
                TAG,
                "Failed to initialize plugin. id=" + pluginId + ", class=" + plugin.getClass().getName(),
                e
            );
            return false;
        }
    }
    
    /**
     * Unregister a plugin
     */
    public void unregisterPlugin(String pluginId) {
        Plugin plugin = plugins.remove(pluginId);
        if (plugin != null) {
            plugin.destroy();
            Log.d(TAG, "Unregistered plugin: " + plugin.getName());
        }
    }
    
    /**
     * Get a plugin by ID
     */
    public Plugin getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }
    
    /**
     * Get all registered plugins
     */
    public List<Plugin> getAllPlugins() {
        return new ArrayList<>(plugins.values());
    }
    
    /**
     * Get all enabled plugins
     */
    public List<Plugin> getEnabledPlugins() {
        List<Plugin> enabledPlugins = new ArrayList<>();
        for (Plugin plugin : plugins.values()) {
            if (plugin.isEnabled()) {
                enabledPlugins.add(plugin);
            }
        }
        return enabledPlugins;
    }
    
    /**
     * Execute a plugin by ID
     */
    public PluginResult executePlugin(String pluginId, String input) {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return PluginResult.error("Plugin not found: " + pluginId, 0);
        }
        
        if (!plugin.isEnabled()) {
            return PluginResult.error("Plugin is disabled: " + pluginId, 0);
        }
        
        long startTime = System.currentTimeMillis();

        PolicyGuardrailPlugin guardrail = getGuardrailPlugin();
        if (guardrail != null && !PolicyGuardrailPlugin.class.getName().equals(plugin.getClass().getName())) {
            PluginResult policy = guardrail.evaluate(pluginId, input);
            if (!policy.isSuccess()) {
                return PluginResult.error(policy.getErrorMessage(), System.currentTimeMillis() - startTime);
            }
        }

        try {
            PluginResult result = plugin.execute(input);
            Log.d(TAG, "Executed plugin " + plugin.getName() + ": " + result);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            Log.e(TAG, "Plugin execution failed: " + plugin.getName(), e);
            return PluginResult.error("Execution failed: " + e.getMessage(), duration);
        }
    }
    
    private PolicyGuardrailPlugin getGuardrailPlugin() {
        Plugin plugin = plugins.get("policy_guardrail");
        if (plugin instanceof PolicyGuardrailPlugin) {
            return (PolicyGuardrailPlugin) plugin;
        }
        return null;
    }

    /**
     * Clean up all plugins
     */
    public void destroy() {
        for (Plugin plugin : plugins.values()) {
            plugin.destroy();
        }
        plugins.clear();
        Log.d(TAG, "PluginManager destroyed");
    }
}
