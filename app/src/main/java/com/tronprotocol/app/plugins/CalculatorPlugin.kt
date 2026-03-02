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
        val fromUnitName = parts[1].lowercase()
        val toUnitName = parts[3].lowercase()

        val fromUnit = Unit.fromString(fromUnitName)
            ?: throw Exception("Unknown unit: '$fromUnitName'")
        val toUnit = Unit.fromString(toUnitName)
            ?: throw Exception("Unknown unit: '$toUnitName'")

        if (fromUnit.type != toUnit.type) {
            throw Exception("Cannot convert from '$fromUnitName' (${fromUnit.type}) to '$toUnitName' (${toUnit.type}). Incompatible types.")
        }

        return if (fromUnit.type == UnitType.TEMPERATURE) {
            convertTemperature(value, fromUnit, toUnit)
        } else {
            // Linear conversion: value * (fromFactor / toFactor)
            // Or: (value * fromFactor) / toFactor
            // Example: 1 km to m -> 1 * 1000.0 / 1.0 = 1000.0
            // Example: 1000 m to km -> 1000 * 1.0 / 1000.0 = 1.0
            value * fromUnit.baseFactor / toUnit.baseFactor
        }
    }

    private fun convertTemperature(value: Double, from: Unit, to: Unit): Double {
        // Convert to Celsius first
        val celsius = when (from) {
            Unit.CELSIUS -> value
            Unit.FAHRENHEIT -> (value - 32) * 5.0 / 9.0
            Unit.KELVIN -> value - 273.15
            else -> throw Exception("Unsupported temperature unit: ${from.names[0]}")
        }

        // Convert from Celsius to target
        return when (to) {
            Unit.CELSIUS -> celsius
            Unit.FAHRENHEIT -> celsius * 9.0 / 5.0 + 32
            Unit.KELVIN -> celsius + 273.15
            else -> throw Exception("Unsupported temperature unit: ${to.names[0]}")
        }
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
