package com.tronprotocol.app.plugins;

import android.content.Context;

/**
 * Calculator Plugin
 * 
 * Provides mathematical calculations and unit conversions
 * Inspired by ToolNeuron's CalculatorPlugin and landseek's tools
 */
public class CalculatorPlugin implements Plugin {
    private static final String TAG = "CalculatorPlugin";
    private static final String ID = "calculator";
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Calculator";
    }
    
    @Override
    public String getDescription() {
        return "Evaluate mathematical expressions and perform unit conversions. " +
               "Supports +, -, *, /, ^, sqrt, sin, cos, tan, log, and unit conversions.";
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
            double result;
            
            // Check if it's a unit conversion (format: "value from_unit to_unit")
            if (input.contains(" to ")) {
                result = handleUnitConversion(input);
            } else {
                // Mathematical expression
                result = evaluateExpression(input);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.success(String.valueOf(result), duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.error("Calculation failed: " + e.getMessage(), duration);
        }
    }
    
    /**
     * Evaluate mathematical expression
     * Simplified implementation - in production use proper expression parser
     */
    private double evaluateExpression(String expr) throws Exception {
        expr = expr.trim().toLowerCase();
        
        // Handle functions
        if (expr.startsWith("sqrt(") && expr.endsWith(")")) {
            String arg = expr.substring(5, expr.length() - 1);
            return Math.sqrt(evaluateExpression(arg));
        }
        if (expr.startsWith("sin(") && expr.endsWith(")")) {
            String arg = expr.substring(4, expr.length() - 1);
            return Math.sin(Math.toRadians(evaluateExpression(arg)));
        }
        if (expr.startsWith("cos(") && expr.endsWith(")")) {
            String arg = expr.substring(4, expr.length() - 1);
            return Math.cos(Math.toRadians(evaluateExpression(arg)));
        }
        if (expr.startsWith("tan(") && expr.endsWith(")")) {
            String arg = expr.substring(4, expr.length() - 1);
            return Math.tan(Math.toRadians(evaluateExpression(arg)));
        }
        if (expr.startsWith("log(") && expr.endsWith(")")) {
            String arg = expr.substring(4, expr.length() - 1);
            return Math.log10(evaluateExpression(arg));
        }
        if (expr.startsWith("ln(") && expr.endsWith(")")) {
            String arg = expr.substring(3, expr.length() - 1);
            return Math.log(evaluateExpression(arg));
        }
        
        // Handle constants
        expr = expr.replace("pi", String.valueOf(Math.PI));
        expr = expr.replace("e", String.valueOf(Math.E));
        
        // Handle power operator
        if (expr.contains("^")) {
            String[] parts = expr.split("\\^", 2);
            return Math.pow(evaluateExpression(parts[0]), evaluateExpression(parts[1]));
        }
        
        // Handle basic arithmetic (left to right, simplified)
        // In production, implement proper operator precedence
        
        // Division
        if (expr.contains("/")) {
            String[] parts = expr.split("/", 2);
            return evaluateExpression(parts[0]) / evaluateExpression(parts[1]);
        }
        
        // Multiplication
        if (expr.contains("*")) {
            String[] parts = expr.split("\\*", 2);
            return evaluateExpression(parts[0]) * evaluateExpression(parts[1]);
        }
        
        // Addition
        if (expr.contains("+")) {
            String[] parts = expr.split("\\+", 2);
            return evaluateExpression(parts[0]) + evaluateExpression(parts[1]);
        }
        
        // Subtraction (be careful with negative numbers)
        int lastMinus = expr.lastIndexOf('-');
        if (lastMinus > 0) {  // Not at beginning
            String[] parts = new String[]{expr.substring(0, lastMinus), expr.substring(lastMinus + 1)};
            return evaluateExpression(parts[0]) - evaluateExpression(parts[1]);
        }
        
        // Parse number
        return Double.parseDouble(expr);
    }
    
    /**
     * Handle unit conversions
     * Format: "value from_unit to_unit"
     */
    private double handleUnitConversion(String input) throws Exception {
        String[] parts = input.split("\\s+");
        if (parts.length < 4) {
            throw new Exception("Invalid format. Use: value from_unit to to_unit");
        }
        
        double value = Double.parseDouble(parts[0]);
        String fromUnit = parts[1].toLowerCase();
        String toUnit = parts[3].toLowerCase();
        
        // Temperature conversions
        if (fromUnit.equals("c") || fromUnit.equals("celsius")) {
            if (toUnit.equals("f") || toUnit.equals("fahrenheit")) {
                return value * 9.0 / 5.0 + 32;
            } else if (toUnit.equals("k") || toUnit.equals("kelvin")) {
                return value + 273.15;
            }
        }
        if (fromUnit.equals("f") || fromUnit.equals("fahrenheit")) {
            if (toUnit.equals("c") || toUnit.equals("celsius")) {
                return (value - 32) * 5.0 / 9.0;
            } else if (toUnit.equals("k") || toUnit.equals("kelvin")) {
                return (value - 32) * 5.0 / 9.0 + 273.15;
            }
        }
        
        // Length conversions (to meters first)
        double meters = convertToMeters(value, fromUnit);
        return convertFromMeters(meters, toUnit);
    }
    
    private double convertToMeters(double value, String unit) throws Exception {
        switch (unit) {
            case "m": case "meter": case "meters": return value;
            case "km": case "kilometer": case "kilometers": return value * 1000;
            case "cm": case "centimeter": case "centimeters": return value / 100;
            case "mm": case "millimeter": case "millimeters": return value / 1000;
            case "mi": case "mile": case "miles": return value * 1609.34;
            case "ft": case "foot": case "feet": return value * 0.3048;
            case "in": case "inch": case "inches": return value * 0.0254;
            default: throw new Exception("Unknown unit: " + unit);
        }
    }
    
    private double convertFromMeters(double meters, String unit) throws Exception {
        switch (unit) {
            case "m": case "meter": case "meters": return meters;
            case "km": case "kilometer": case "kilometers": return meters / 1000;
            case "cm": case "centimeter": case "centimeters": return meters * 100;
            case "mm": case "millimeter": case "millimeters": return meters * 1000;
            case "mi": case "mile": case "miles": return meters / 1609.34;
            case "ft": case "foot": case "feet": return meters / 0.3048;
            case "in": case "inch": case "inches": return meters / 0.0254;
            default: throw new Exception("Unknown unit: " + unit);
        }
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
