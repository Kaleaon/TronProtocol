package com.tronprotocol.app.plugins

import android.content.Context

/**
 * Calculator Plugin.
 *
 * Provides mathematical calculations and unit conversions.
 * Uses a recursive descent parser for proper operator precedence.
 *
 * Operator precedence (low to high):
 *   1. Addition, Subtraction (+, -)
 *   2. Multiplication, Division, Modulo (*, /, %)
 *   3. Exponentiation (^)
 *   4. Unary minus (-)
 *   5. Functions (sqrt, sin, cos, tan, log, ln, abs, ceil, floor)
 *   6. Parentheses
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
            "Supports +, -, *, /, %, ^, sqrt, sin, cos, tan, log, ln, abs, ceil, floor, " +
            "parentheses, pi, e constants, and unit conversions (temperature, length, weight)."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val result: String = if (input.contains(" to ")) {
                formatNumber(handleUnitConversion(input))
            } else {
                formatNumber(evaluateExpression(input))
            }

            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(result, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("Calculation failed: ${e.message}", duration)
        }
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble() && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    // ---- Recursive Descent Parser ----

    /**
     * Evaluate a mathematical expression using recursive descent parsing
     * with proper operator precedence.
     */
    fun evaluateExpression(expr: String): Double {
        val parser = Parser(expr.trim())
        val result = parser.parseExpression()
        if (parser.pos < parser.tokens.size) {
            throw Exception("Unexpected token: '${parser.tokens[parser.pos].value}'")
        }
        return result
    }

    private enum class TokenType {
        NUMBER, PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
        LPAREN, RPAREN, COMMA, FUNCTION, EOF
    }

    private data class Token(val type: TokenType, val value: String)

    private class Parser(input: String) {
        val tokens: List<Token> = tokenize(input)
        var pos: Int = 0

        private fun tokenize(input: String): List<Token> {
            val result = mutableListOf<Token>()
            var i = 0
            val s = input.trim()

            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '+' -> { result.add(Token(TokenType.PLUS, "+")); i++ }
                    c == '-' -> { result.add(Token(TokenType.MINUS, "-")); i++ }
                    c == '*' -> { result.add(Token(TokenType.STAR, "*")); i++ }
                    c == '/' -> { result.add(Token(TokenType.SLASH, "/")); i++ }
                    c == '%' -> { result.add(Token(TokenType.PERCENT, "%")); i++ }
                    c == '^' -> { result.add(Token(TokenType.CARET, "^")); i++ }
                    c == '(' -> { result.add(Token(TokenType.LPAREN, "(")); i++ }
                    c == ')' -> { result.add(Token(TokenType.RPAREN, ")")); i++ }
                    c == ',' -> { result.add(Token(TokenType.COMMA, ",")); i++ }
                    c.isDigit() || c == '.' -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                        result.add(Token(TokenType.NUMBER, s.substring(start, i)))
                    }
                    c.isLetter() -> {
                        val start = i
                        while (i < s.length && s[i].isLetter()) i++
                        val word = s.substring(start, i).lowercase()
                        when (word) {
                            "pi" -> result.add(Token(TokenType.NUMBER, Math.PI.toString()))
                            "e" -> result.add(Token(TokenType.NUMBER, Math.E.toString()))
                            else -> result.add(Token(TokenType.FUNCTION, word))
                        }
                    }
                    else -> throw Exception("Unexpected character: '$c'")
                }
            }
            return result
        }

        private fun peek(): Token =
            if (pos < tokens.size) tokens[pos] else Token(TokenType.EOF, "")

        private fun consume(): Token {
            val t = peek()
            pos++
            return t
        }

        /**
         * expression := term (('+' | '-') term)*
         */
        fun parseExpression(): Double {
            var result = parseTerm()
            while (true) {
                when (peek().type) {
                    TokenType.PLUS -> { consume(); result += parseTerm() }
                    TokenType.MINUS -> { consume(); result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        /**
         * term := power (('*' | '/' | '%') power)*
         */
        private fun parseTerm(): Double {
            var result = parsePower()
            while (true) {
                when (peek().type) {
                    TokenType.STAR -> { consume(); result *= parsePower() }
                    TokenType.SLASH -> {
                        consume()
                        val divisor = parsePower()
                        if (divisor == 0.0) throw Exception("Division by zero")
                        result /= divisor
                    }
                    TokenType.PERCENT -> {
                        consume()
                        val divisor = parsePower()
                        if (divisor == 0.0) throw Exception("Modulo by zero")
                        result %= divisor
                    }
                    else -> break
                }
            }
            return result
        }

        /**
         * power := unary ('^' power)?  (right-associative)
         */
        private fun parsePower(): Double {
            val base = parseUnary()
            return if (peek().type == TokenType.CARET) {
                consume()
                val exponent = parsePower() // right-associative
                Math.pow(base, exponent)
            } else {
                base
            }
        }

        /**
         * unary := '-' unary | primary
         */
        private fun parseUnary(): Double {
            return if (peek().type == TokenType.MINUS) {
                consume()
                -parseUnary()
            } else if (peek().type == TokenType.PLUS) {
                consume()
                parseUnary()
            } else {
                parsePrimary()
            }
        }

        /**
         * primary := NUMBER | FUNCTION '(' expression (',' expression)* ')' | '(' expression ')'
         */
        private fun parsePrimary(): Double {
            val token = peek()
            return when (token.type) {
                TokenType.NUMBER -> {
                    consume()
                    token.value.toDoubleOrNull()
                        ?: throw Exception("Invalid number: '${token.value}'")
                }
                TokenType.FUNCTION -> {
                    consume()
                    if (peek().type != TokenType.LPAREN) {
                        throw Exception("Expected '(' after function '${token.value}'")
                    }
                    consume() // consume '('
                    val args = mutableListOf(parseExpression())
                    while (peek().type == TokenType.COMMA) {
                        consume()
                        args.add(parseExpression())
                    }
                    if (peek().type != TokenType.RPAREN) {
                        throw Exception("Expected ')' after function arguments")
                    }
                    consume() // consume ')'
                    applyFunction(token.value, args)
                }
                TokenType.LPAREN -> {
                    consume()
                    val result = parseExpression()
                    if (peek().type != TokenType.RPAREN) {
                        throw Exception("Expected ')'")
                    }
                    consume()
                    result
                }
                else -> throw Exception("Unexpected token: '${token.value}'")
            }
        }

        private fun applyFunction(name: String, args: List<Double>): Double {
            if (args.isEmpty()) throw Exception("Function '$name' requires at least one argument")
            val arg = args[0]
            return when (name) {
                "sqrt" -> {
                    if (arg < 0) throw Exception("sqrt of negative number")
                    Math.sqrt(arg)
                }
                "sin" -> Math.sin(Math.toRadians(arg))
                "cos" -> Math.cos(Math.toRadians(arg))
                "tan" -> Math.tan(Math.toRadians(arg))
                "log" -> {
                    if (arg <= 0) throw Exception("log of non-positive number")
                    Math.log10(arg)
                }
                "ln" -> {
                    if (arg <= 0) throw Exception("ln of non-positive number")
                    Math.log(arg)
                }
                "abs" -> Math.abs(arg)
                "ceil" -> Math.ceil(arg)
                "floor" -> Math.floor(arg)
                "round" -> Math.round(arg).toDouble()
                "max" -> {
                    if (args.size < 2) throw Exception("max requires two arguments")
                    maxOf(args[0], args[1])
                }
                "min" -> {
                    if (args.size < 2) throw Exception("min requires two arguments")
                    minOf(args[0], args[1])
                }
                else -> throw Exception("Unknown function: '$name'")
            }
        }
    }

    // ---- Unit Conversions ----

    /**
     * Handle unit conversions.
     * Format: "value from_unit to to_unit"
     */
    fun handleUnitConversion(input: String): Double {
        val parts = input.split("\\s+".toRegex())
        if (parts.size < 4) {
            throw Exception("Invalid format. Use: value from_unit to to_unit")
        }

        val value = parts[0].toDoubleOrNull()
            ?: throw Exception("Invalid number: '${parts[0]}'")
        val fromUnit = parts[1].lowercase()
        val toUnit = parts[3].lowercase()

        // Temperature conversions (special - non-linear)
        val tempResult = convertTemperature(value, fromUnit, toUnit)
        if (tempResult != null) return tempResult

        // Length conversions
        val lengthFrom = lengthToMeters(fromUnit)
        val lengthTo = lengthToMeters(toUnit)
        if (lengthFrom != null && lengthTo != null) {
            return value * lengthFrom / lengthTo
        }

        // Weight/mass conversions
        val weightFrom = weightToKilograms(fromUnit)
        val weightTo = weightToKilograms(toUnit)
        if (weightFrom != null && weightTo != null) {
            return value * weightFrom / weightTo
        }

        // Time conversions
        val timeFrom = timeToSeconds(fromUnit)
        val timeTo = timeToSeconds(toUnit)
        if (timeFrom != null && timeTo != null) {
            return value * timeFrom / timeTo
        }

        // Data size conversions
        val dataFrom = dataToBytes(fromUnit)
        val dataTo = dataToBytes(toUnit)
        if (dataFrom != null && dataTo != null) {
            return value * dataFrom / dataTo
        }

        throw Exception("Cannot convert from '$fromUnit' to '$toUnit'. Unsupported units or incompatible types.")
    }

    private fun convertTemperature(value: Double, from: String, to: String): Double? {
        val tempUnits = setOf("c", "celsius", "f", "fahrenheit", "k", "kelvin")
        if (from !in tempUnits || to !in tempUnits) return null

        // Convert to Celsius first
        val celsius = when (from) {
            "c", "celsius" -> value
            "f", "fahrenheit" -> (value - 32) * 5.0 / 9.0
            "k", "kelvin" -> value - 273.15
            else -> return null
        }

        // Convert from Celsius to target
        return when (to) {
            "c", "celsius" -> celsius
            "f", "fahrenheit" -> celsius * 9.0 / 5.0 + 32
            "k", "kelvin" -> celsius + 273.15
            else -> null
        }
    }

    /** Returns the factor to multiply by to convert to meters, or null if not a length unit. */
    private fun lengthToMeters(unit: String): Double? = when (unit) {
        "m", "meter", "meters" -> 1.0
        "km", "kilometer", "kilometers" -> 1000.0
        "cm", "centimeter", "centimeters" -> 0.01
        "mm", "millimeter", "millimeters" -> 0.001
        "mi", "mile", "miles" -> 1609.344
        "ft", "foot", "feet" -> 0.3048
        "in", "inch", "inches" -> 0.0254
        "yd", "yard", "yards" -> 0.9144
        "nm", "nautical_mile", "nautical_miles" -> 1852.0
        else -> null
    }

    /** Returns the factor to multiply by to convert to kilograms, or null if not a weight unit. */
    private fun weightToKilograms(unit: String): Double? = when (unit) {
        "kg", "kilogram", "kilograms" -> 1.0
        "g", "gram", "grams" -> 0.001
        "mg", "milligram", "milligrams" -> 0.000001
        "lb", "lbs", "pound", "pounds" -> 0.453592
        "oz", "ounce", "ounces" -> 0.0283495
        "ton", "tons", "tonne", "tonnes" -> 1000.0
        "st", "stone", "stones" -> 6.35029
        else -> null
    }

    /** Returns the factor to multiply by to convert to seconds, or null if not a time unit. */
    private fun timeToSeconds(unit: String): Double? = when (unit) {
        "s", "sec", "second", "seconds" -> 1.0
        "ms", "millisecond", "milliseconds" -> 0.001
        "min", "minute", "minutes" -> 60.0
        "h", "hr", "hour", "hours" -> 3600.0
        "d", "day", "days" -> 86400.0
        "w", "week", "weeks" -> 604800.0
        else -> null
    }

    /** Returns the factor to multiply by to convert to bytes, or null if not a data unit. */
    private fun dataToBytes(unit: String): Double? = when (unit) {
        "b", "byte", "bytes" -> 1.0
        "kb", "kilobyte", "kilobytes" -> 1024.0
        "mb", "megabyte", "megabytes" -> 1048576.0
        "gb", "gigabyte", "gigabytes" -> 1073741824.0
        "tb", "terabyte", "terabytes" -> 1099511627776.0
        else -> null
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
