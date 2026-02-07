package com.tronprotocol.app.plugins;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * DateTime Plugin
 * 
 * Provides date/time operations and timezone conversions
 * Inspired by ToolNeuron's DateTimePlugin and landseek's tools
 */
public class DateTimePlugin implements Plugin {
    private static final String TAG = "DateTimePlugin";
    private static final String ID = "datetime";
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Date & Time";
    }
    
    @Override
    public String getDescription() {
        return "Get current date/time, convert timezones, calculate date differences. " +
               "Commands: 'now', 'now UTC', 'add 5 days', 'diff 2024-01-01'";
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
        
        try {
            String result;
            input = input.trim().toLowerCase();
            
            if (input.startsWith("now")) {
                result = getCurrentTime(input);
            } else if (input.startsWith("add") || input.startsWith("subtract")) {
                result = calculateDate(input);
            } else if (input.startsWith("diff")) {
                result = calculateDifference(input);
            } else if (input.startsWith("format")) {
                result = formatDate(input);
            } else {
                result = getCurrentTime("now");
            }
            
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.success(result, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.error("DateTime operation failed: " + e.getMessage(), duration);
        }
    }
    
    /**
     * Get current time in specified timezone
     */
    private String getCurrentTime(String command) {
        String[] parts = command.split("\\s+");
        String timezone = parts.length > 1 ? parts[1].toUpperCase() : "default";
        
        SimpleDateFormat sdf;
        if (timezone.equals("default")) {
            sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault());
        } else {
            sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone(timezone));
        }
        
        return sdf.format(new Date());
    }
    
    /**
     * Calculate future/past date
     * Format: "add 5 days" or "subtract 3 weeks"
     */
    private String calculateDate(String command) throws Exception {
        String[] parts = command.split("\\s+");
        if (parts.length < 3) {
            throw new Exception("Invalid format. Use: add/subtract number unit");
        }
        
        boolean isAdd = parts[0].equals("add");
        int amount = Integer.parseInt(parts[1]);
        String unit = parts[2];
        
        if (!isAdd) {
            amount = -amount;
        }
        
        Calendar cal = Calendar.getInstance();
        
        switch (unit) {
            case "second":
            case "seconds":
                cal.add(Calendar.SECOND, amount);
                break;
            case "minute":
            case "minutes":
                cal.add(Calendar.MINUTE, amount);
                break;
            case "hour":
            case "hours":
                cal.add(Calendar.HOUR, amount);
                break;
            case "day":
            case "days":
                cal.add(Calendar.DAY_OF_MONTH, amount);
                break;
            case "week":
            case "weeks":
                cal.add(Calendar.WEEK_OF_YEAR, amount);
                break;
            case "month":
            case "months":
                cal.add(Calendar.MONTH, amount);
                break;
            case "year":
            case "years":
                cal.add(Calendar.YEAR, amount);
                break;
            default:
                throw new Exception("Unknown unit: " + unit);
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(cal.getTime());
    }
    
    /**
     * Calculate difference between now and a date
     * Format: "diff 2024-01-01"
     */
    private String calculateDifference(String command) throws Exception {
        String[] parts = command.split("\\s+");
        if (parts.length < 2) {
            throw new Exception("Invalid format. Use: diff YYYY-MM-DD");
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Date targetDate = sdf.parse(parts[1]);
        Date now = new Date();
        
        long diffMs = targetDate.getTime() - now.getTime();
        long diffDays = diffMs / (1000 * 60 * 60 * 24);
        long diffHours = (diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
        
        String direction = diffMs >= 0 ? "from now" : "ago";
        diffDays = Math.abs(diffDays);
        diffHours = Math.abs(diffHours);
        
        return diffDays + " days and " + diffHours + " hours " + direction;
    }
    
    /**
     * Format current date with custom format
     * Format: "format YYYY-MM-DD"
     */
    private String formatDate(String command) throws Exception {
        String[] parts = command.split("\\s+", 2);
        if (parts.length < 2) {
            throw new Exception("Invalid format. Use: format FORMAT_STRING");
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat(parts[1], Locale.getDefault());
        return sdf.format(new Date());
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
