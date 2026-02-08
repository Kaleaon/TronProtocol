package com.tronprotocol.app.plugins;

import android.content.Context;
import android.os.Build;

/**
 * Plugin that provides device information
 * 
 * Example plugin inspired by ToolNeuron's dev utilities
 */
public class DeviceInfoPlugin implements Plugin {
    private static final String ID = "device_info";
    private static final String NAME = "Device Info";
    private static final String DESCRIPTION = "Provides device and system information";
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return NAME;
    }
    
    @Override
    public String getDescription() {
        return DESCRIPTION;
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
    public PluginResult execute(String input) throws Exception {
        long startTime = System.currentTimeMillis();
        
        StringBuilder info = new StringBuilder();
        info.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("Android Version: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        info.append("Brand: ").append(Build.BRAND).append("\n");
        info.append("Device: ").append(Build.DEVICE).append("\n");
        info.append("Hardware: ").append(Build.HARDWARE).append("\n");
        info.append("Product: ").append(Build.PRODUCT).append("\n");
        
        // Add memory info if context is available
        if (context != null) {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(memInfo);
                
                long totalMB = memInfo.totalMem / (1024 * 1024);
                long availMB = memInfo.availMem / (1024 * 1024);
                
                info.append("Total Memory: ").append(totalMB).append(" MB\n");
                info.append("Available Memory: ").append(availMB).append(" MB\n");
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        return PluginResult.success(info.toString(), duration);
    }
    
    @Override
    public void initialize(Context context) {
        this.context = context;
    }
    
    @Override
    public void destroy() {
        this.context = null;
    }
}
