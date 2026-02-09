package com.tronprotocol.app.plugins

import android.content.Context

/**
 * Calculator Plugin.
 *
 * Provides mathematical calculations and unit conversions.
 * Inspired by ToolNeuron's CalculatorPlugin and landseek's tools.
 */
class CalculatorPlugin : Plugin {

    companion object {
        private const val TAG = "CalculatorPlugin"
        private const val ID = "calculator"
    }

    private var context: Context? = null

    override val id: String = ID

    override val name: String = "Calculator"

    override val description: String =
        "Evaluate mathematical expressions and perform unit conversions. " +
            "Supports +, -, *, /, ^, sqrt, sin, cos, tan, log, and unit conversions."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val result: Double = if (input.contains(" to ")) {
                handleUnitConversion(input)
            } else {
                evaluateExpression(input)
            }

            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(result.toString(), duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("Calculation failed: ${e.message}", duration)
        }
    }

    /**
     * Evaluate mathematical expression.
     * Simplified implementation - in production use proper expression parser.
     */
    private fun evaluateExpression(expr: String): Double {
        var e = expr.trim().lowercase()

        // Handle functions
        if (e.startsWith("sqrt(") && e.endsWith(")")) {
            val arg = e.substring(5, e.length - 1)
            return Math.sqrt(evaluateExpression(arg))
        }
        if (e.startsWith("sin(") && e.endsWith(")")) {
            val arg = e.substring(4, e.length - 1)
            return Math.sin(Math.toRadians(evaluateExpression(arg)))
        }
        if (e.startsWith("cos(") && e.endsWith(")")) {
            val arg = e.substring(4, e.length - 1)
            return Math.cos(Math.toRadians(evaluateExpression(arg)))
        }
        if (e.startsWith("tan(") && e.endsWith(")")) {
            val arg = e.substring(4, e.length - 1)
            return Math.tan(Math.toRadians(evaluateExpression(arg)))
        }
        if (e.startsWith("log(") && e.endsWith(")")) {
            val arg = e.substring(4, e.length - 1)
            return Math.log10(evaluateExpression(arg))
        }
        if (e.startsWith("ln(") && e.endsWith(")")) {
            val arg = e.substring(3, e.length - 1)
            return Math.log(evaluateExpression(arg))
        }

        // Handle constants
        e = e.replace("pi", Math.PI.toString())
        e = e.replace("e", Math.E.toString())

        // Handle power operator
        if (e.contains("^")) {
            val parts = e.split("\\^".toRegex(), 2)
            return Math.pow(evaluateExpression(parts[0]), evaluateExpression(parts[1]))
        }

        // Handle basic arithmetic (left to right, simplified)
        // In production, implement proper operator precedence

        // Division
        if (e.contains("/")) {
            val parts = e.split("/".toRegex(), 2)
            return evaluateExpression(parts[0]) / evaluateExpression(parts[1])
        }

        // Multiplication
        if (e.contains("*")) {
            val parts = e.split("\\*".toRegex(), 2)
            return evaluateExpression(parts[0]) * evaluateExpression(parts[1])
        }

        // Addition
        if (e.contains("+")) {
            val parts = e.split("\\+".toRegex(), 2)
            return evaluateExpression(parts[0]) + evaluateExpression(parts[1])
        }

        // Subtraction (be careful with negative numbers)
        val lastMinus = e.lastIndexOf('-')
        if (lastMinus > 0) { // Not at beginning
            val left = e.substring(0, lastMinus)
            val right = e.substring(lastMinus + 1)
            return evaluateExpression(left) - evaluateExpression(right)
        }

        // Parse number
        return e.toDouble()
    }

    /**
     * Handle unit conversions.
     * Format: "value from_unit to to_unit"
     */
    private fun handleUnitConversion(input: String): Double {
        val parts = input.split("\\s+".toRegex())
        if (parts.size < 4) {
            throw Exception("Invalid format. Use: value from_unit to to_unit")
        }

        val value = parts[0].toDouble()
        val fromUnit = parts[1].lowercase()
        val toUnit = parts[3].lowercase()

        // Temperature conversions
        if (fromUnit == "c" || fromUnit == "celsius") {
            if (toUnit == "f" || toUnit == "fahrenheit") {
                return value * 9.0 / 5.0 + 32
            } else if (toUnit == "k" || toUnit == "kelvin") {
                return value + 273.15
            }
        }
        if (fromUnit == "f" || fromUnit == "fahrenheit") {
            if (toUnit == "c" || toUnit == "celsius") {
                return (value - 32) * 5.0 / 9.0
            } else if (toUnit == "k" || toUnit == "kelvin") {
                return (value - 32) * 5.0 / 9.0 + 273.15
            }
        }

        // Length conversions (to meters first)
        val meters = convertToMeters(value, fromUnit)
        return convertFromMeters(meters, toUnit)
    }

    private fun convertToMeters(value: Double, unit: String): Double = when (unit) {
        "m", "meter", "meters" -> value
        "km", "kilometer", "kilometers" -> value * 1000
        "cm", "centimeter", "centimeters" -> value / 100
        "mm", "millimeter", "millimeters" -> value / 1000
        "mi", "mile", "miles" -> value * 1609.34
        "ft", "foot", "feet" -> value * 0.3048
        "in", "inch", "inches" -> value * 0.0254
        else -> throw Exception("Unknown unit: $unit")
    }

    private fun convertFromMeters(meters: Double, unit: String): Double = when (unit) {
        "m", "meter", "meters" -> meters
        "km", "kilometer", "kilometers" -> meters / 1000
        "cm", "centimeter", "centimeters" -> meters * 100
        "mm", "millimeter", "millimeters" -> meters * 1000
        "mi", "mile", "miles" -> meters / 1609.34
        "ft", "foot", "feet" -> meters / 0.3048
        "in", "inch", "inches" -> meters / 0.0254
        else -> throw Exception("Unknown unit: $unit")
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
