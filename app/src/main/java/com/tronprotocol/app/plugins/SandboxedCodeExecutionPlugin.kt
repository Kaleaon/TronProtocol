package com.tronprotocol.app.plugins

import android.content.Context
import android.text.TextUtils
import android.util.Base64
import org.json.JSONObject

/**
 * Restricted sandbox-like execution plugin.
 * Does not execute native shell or arbitrary process code.
 */
class SandboxedCodeExecutionPlugin : Plugin {

    companion object {
        private const val ID = "sandbox_exec"
        private const val MAX_INPUT = 4000
    }

    override val id: String = ID

    override val name: String = "Sandbox Exec"

    override val description: String =
        "Restricted execution primitives. Commands: calc|expression, json_get|json|field, b64_encode|text, b64_decode|text, upper|text, lower|text"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start))
            }
            if (input.length > MAX_INPUT) {
                return PluginResult.error("Input too large", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "calc" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: calc|expression", elapsed(start))
                    PluginResult.success(evalMath(parts[1].trim()).toString(), elapsed(start))
                }
                "json_get" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: json_get|json|field", elapsed(start))
                    val json = JSONObject(parts[1])
                    if (!json.has(parts[2])) {
                        return PluginResult.error("Field not found: ${parts[2]}", elapsed(start))
                    }
                    PluginResult.success(json.get(parts[2]).toString(), elapsed(start))
                }
                "b64_encode" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: b64_encode|text", elapsed(start))
                    PluginResult.success(Base64.encodeToString(parts[1].toByteArray(), Base64.NO_WRAP), elapsed(start))
                }
                "b64_decode" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: b64_decode|text", elapsed(start))
                    PluginResult.success(String(Base64.decode(parts[1], Base64.DEFAULT)), elapsed(start))
                }
                "upper" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: upper|text", elapsed(start))
                    PluginResult.success(parts[1].uppercase(), elapsed(start))
                }
                "lower" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: lower|text", elapsed(start))
                    PluginResult.success(parts[1].lowercase(), elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Sandbox execution failed: ${e.message}", elapsed(start))
        }
    }

    private fun evalMath(expression: String): Double {
        if (!expression.matches(Regex("[0-9+\\-*/(). ]+"))) {
            throw IllegalArgumentException("Expression contains unsupported characters")
        }
        return ExpressionParser(expression).parse()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        // No-op
    }

    override fun destroy() {
        // No-op
    }

    /**
     * Small math parser supporting +, -, *, / and parentheses.
     */
    private class ExpressionParser(private val s: String) {
        private var pos = -1
        private var ch = 0

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < s.length) throw RuntimeException("Unexpected: ${ch.toChar()}")
            return x
        }

        private fun nextChar() {
            ch = if (++pos < s.length) s[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> x /= parseFactor()
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            val startPos = this.pos
            val x: Double
            if (eat('('.code)) {
                x = parseExpression()
                eat(')'.code)
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = s.substring(startPos, this.pos).toDouble()
            } else {
                throw RuntimeException("Unexpected: ${ch.toChar()}")
            }

            return x
        }
    }
}
