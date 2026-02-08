package com.tronprotocol.app.plugins;

/**
 * Base interface for plugins in TronProtocol
 * 
 * Inspired by ToolNeuron's extensible plugin architecture
 */
public interface Plugin {
    /**
     * Get the unique identifier for this plugin
     */
    String getId();
    
    /**
     * Get the display name of this plugin
     */
    String getName();
    
    /**
     * Get the description of what this plugin does
     */
    String getDescription();
    
    /**
     * Check if the plugin is enabled
     */
    boolean isEnabled();
    
    /**
     * Enable or disable the plugin
     */
    void setEnabled(boolean enabled);
    
    /**
     * Execute the plugin's main functionality
     * @param input Input data for the plugin
     * @return Result of plugin execution
     */
    PluginResult execute(String input) throws Exception;
    
    /**
     * Initialize the plugin with context
     */
    void initialize(android.content.Context context);
    
    /**
     * Clean up resources when plugin is destroyed
     */
    void destroy();
}
