package com.tronprotocol.app.plugins

import android.content.Context
import org.json.JSONObject

/**
 * Enhanced expression evaluator for user-defined automations.
 * Extends sandboxed execution with variables, conditionals, and loops.
 *
 * Commands:
 *   eval|expression          – Evaluate expression with variables
 *   set_var|name|value       – Set a variable
 *   get_var|name             – Get a variable
 *   list_vars                – List all variables
 *   if|condition|then|else   – Conditional execution
 *   template|text            – String template with variable substitution ({var_name})
 *   clear_vars               – Clear all variables
 */
class ScriptingRuntimePlugin : Plugin {

    override val id: String = ID
    override val name: String = "Scripting Runtime"
    override val description: String =
        "Expression evaluator with variables. Commands: eval|expr, set_var|name|value, get_var|name, list_vars, if|cond|then|else, template|text, clear_vars"
    override var isEnabled: Boolean = true

    private val variables = mutableMapOf<String, String>()

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "eval" -> {
                    val expr = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: eval|expression", elapsed(start))
                    val resolved = resolveVars(expr)
                    // Try numeric evaluation
                    if (resolved.matches(Regex("[0-9+\\-*/(). ]+"))) {
                        val result = evalMath(resolved)
                        PluginResult.success(result.toString(), elapsed(start))
                    } else {
                        PluginResult.success(resolved, elapsed(start))
                    }
                }
                "set_var" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: set_var|name|value", elapsed(start))
                    val name = parts[1].trim()
                    val value = parts[2].trim()
                    variables[name] = value
                    PluginResult.success("$name = $value", elapsed(start))
                }
                "get_var" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: get_var|name", elapsed(start))
                    val value = variables[name]
                    if (value != null) {
                        PluginResult.success("$name = $value", elapsed(start))
                    } else {
                        PluginResult.error("Variable not found: $name", elapsed(start))
                    }
                }
                "list_vars" -> {
                    val json = JSONObject()
                    variables.forEach { (k, v) -> json.put(k, v) }
                    PluginResult.success("Variables (${variables.size}):\n${json.toString(2)}", elapsed(start))
                }
                "if" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: if|condition|then_value|else_value", elapsed(start))
                    val condition = resolveVars(parts[1].trim())
                    val thenVal = resolveVars(parts[2].trim())
                    val elseVal = resolveVars(parts[3].trim())
                    val result = if (evaluateCondition(condition)) thenVal else elseVal
                    PluginResult.success(result, elapsed(start))
                }
                "template" -> {
                    val text = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: template|text with {var} placeholders", elapsed(start))
                    val resolved = resolveVars(text)
                    PluginResult.success(resolved, elapsed(start))
                }
                "clear_vars" -> {
                    variables.clear()
                    PluginResult.success("All variables cleared", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Scripting error: ${e.message}", elapsed(start))
        }
    }

    private fun resolveVars(text: String): String {
        var result = text
        for ((name, value) in variables) {
            result = result.replace("{$name}", value)
        }
        return result
    }

    private fun evaluateCondition(condition: String): Boolean {
        // Simple condition evaluation
        return when {
            condition.contains("==") -> {
                val sides = condition.split("==")
                sides[0].trim() == sides[1].trim()
            }
            condition.contains("!=") -> {
                val sides = condition.split("!=")
                sides[0].trim() != sides[1].trim()
            }
            condition.contains(">") && !condition.contains(">=") -> {
                val sides = condition.split(">")
                (sides[0].trim().toDoubleOrNull() ?: 0.0) > (sides[1].trim().toDoubleOrNull() ?: 0.0)
            }
            condition.contains("<") && !condition.contains("<=") -> {
                val sides = condition.split("<")
                (sides[0].trim().toDoubleOrNull() ?: 0.0) < (sides[1].trim().toDoubleOrNull() ?: 0.0)
            }
            condition.equals("true", ignoreCase = true) -> true
            condition.equals("false", ignoreCase = true) -> false
            condition.isNotBlank() -> true
            else -> false
        }
    }

    private fun evalMath(expression: String): Double {
        // Delegate to simple parser (similar to SandboxedCodeExecutionPlugin)
        var pos = -1
        var ch = 0
        val s = expression

        fun nextChar() { ch = if (++pos < s.length) s[pos].code else -1 }
        fun eat(c: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == c) { nextChar(); return true }
            return false
        }
        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) when { eat('+'.code) -> x += parseTerm(); eat('-'.code) -> x -= parseTerm(); else -> return x }
        }
        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) when { eat('*'.code) -> x *= parseFactor(); eat('/'.code) -> x /= parseFactor(); else -> return x }
        }
        fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            val sp = pos
            if (eat('('.code)) { val x = parseExpression(); eat(')'.code); return x }
            if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                return s.substring(sp, pos).toDouble()
            }
            throw RuntimeException("Unexpected: ${ch.toChar()}")
        }

        nextChar()
        return parseExpression()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {}
    override fun destroy() { variables.clear() }

    companion object {
        const val ID = "scripting_runtime"
    }
}
