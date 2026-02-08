package com.tronprotocol.app.plugins;

/**
 * Represents the result of a plugin execution
 * 
 * Inspired by ToolNeuron's plugin execution metrics
 */
public class PluginResult {
    private boolean success;
    private String data;
    private String errorMessage;
    private long executionTimeMs;
    
    public PluginResult(boolean success, String data, long executionTimeMs) {
        this.success = success;
        this.data = data;
        this.executionTimeMs = executionTimeMs;
    }
    
    public static PluginResult success(String data, long executionTimeMs) {
        return new PluginResult(true, data, executionTimeMs);
    }
    
    public static PluginResult error(String errorMessage, long executionTimeMs) {
        PluginResult result = new PluginResult(false, null, executionTimeMs);
        result.errorMessage = errorMessage;
        return result;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getData() {
        return data;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public long getExecutionTimeMs() {
        return executionTimeMs;
    }
    
    @Override
    public String toString() {
        return "PluginResult{" +
                "success=" + success +
                ", executionTimeMs=" + executionTimeMs +
                (success ? ", data='" + data + '\'' : ", error='" + errorMessage + '\'') +
                '}';
    }
}
