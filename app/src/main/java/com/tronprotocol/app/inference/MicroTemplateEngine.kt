package com.tronprotocol.app.inference

/**
 * Lightweight Mustache-like template engine for structured prompt construction.
 *
 * Inspired by "Build Your Own Template Engine" (AOSA book / BYOX tutorials).
 * Supports:
 * - Variable substitution: {{variable}}
 * - Conditional blocks: {{#if variable}}...{{/if}} and {{#if variable}}...{{else}}...{{/if}}
 * - Iteration: {{#each list}}...{{/each}} (with {{.}} for current item)
 * - Partials/includes: {{> partialName}}
 * - Escaped output by default; use {{{variable}}} for raw output
 *
 * Thread-safe and allocation-conscious for on-device use.
 */
class MicroTemplateEngine {

    /**
     * Render a template with the given context.
     *
     * @param template Template string with Mustache-like syntax
     * @param context Map of variable names to values (String, List<String>, Boolean, or Any)
     * @param partials Map of partial names to template strings for {{> partial}} resolution
     * @return Rendered string
     */
    fun render(
        template: String,
        context: Map<String, Any>,
        partials: Map<String, String> = emptyMap()
    ): String {
        val tokens = tokenize(template)
        return renderTokens(tokens, context, partials)
    }

    // --- Tokenizer ---

    private sealed class Token {
        data class Text(val value: String) : Token()
        data class Variable(val name: String, val raw: Boolean = false) : Token()
        data class IfOpen(val name: String) : Token()
        object Else : Token()
        data class IfClose(val name: String) : Token()
        data class EachOpen(val name: String) : Token()
        data class EachClose(val name: String) : Token()
        data class Partial(val name: String) : Token()
    }

    private fun tokenize(template: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = template.length

        while (i < len) {
            val openTriple = template.indexOf("{{{", i)
            val openDouble = template.indexOf("{{", i)

            // No more tags
            if (openDouble == -1) {
                tokens.add(Token.Text(template.substring(i)))
                break
            }

            // Raw variable {{{ }}}
            if (openTriple == openDouble) {
                if (openTriple > i) {
                    tokens.add(Token.Text(template.substring(i, openTriple)))
                }
                val closeTriple = template.indexOf("}}}", openTriple + 3)
                if (closeTriple == -1) {
                    tokens.add(Token.Text(template.substring(openTriple)))
                    break
                }
                val name = template.substring(openTriple + 3, closeTriple).trim()
                tokens.add(Token.Variable(name, raw = true))
                i = closeTriple + 3
                continue
            }

            // Text before tag
            if (openDouble > i) {
                tokens.add(Token.Text(template.substring(i, openDouble)))
            }

            val closeDouble = template.indexOf("}}", openDouble + 2)
            if (closeDouble == -1) {
                tokens.add(Token.Text(template.substring(openDouble)))
                break
            }

            val tag = template.substring(openDouble + 2, closeDouble).trim()
            tokens.add(parseTag(tag))
            i = closeDouble + 2
        }

        return tokens
    }

    private fun parseTag(tag: String): Token = when {
        tag.startsWith("#if ") -> Token.IfOpen(tag.removePrefix("#if ").trim())
        tag.startsWith("/if") -> Token.IfClose(tag.removePrefix("/if").trim())
        tag == "else" -> Token.Else
        tag.startsWith("#each ") -> Token.EachOpen(tag.removePrefix("#each ").trim())
        tag.startsWith("/each") -> Token.EachClose(tag.removePrefix("/each").trim())
        tag.startsWith("> ") -> Token.Partial(tag.removePrefix("> ").trim())
        else -> Token.Variable(tag, raw = false)
    }

    // --- Renderer ---

    private fun renderTokens(
        tokens: List<Token>,
        context: Map<String, Any>,
        partials: Map<String, String>
    ): String {
        val sb = StringBuilder()
        var i = 0

        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Token.Text -> {
                    sb.append(token.value)
                    i++
                }
                is Token.Variable -> {
                    val value = resolveValue(token.name, context)
                    sb.append(if (token.raw) value else escapeHtml(value))
                    i++
                }
                is Token.IfOpen -> {
                    val result = renderIf(tokens, i, context, partials)
                    sb.append(result.first)
                    i = result.second
                }
                is Token.EachOpen -> {
                    val result = renderEach(tokens, i, context, partials)
                    sb.append(result.first)
                    i = result.second
                }
                is Token.Partial -> {
                    val partialTemplate = partials[token.name] ?: ""
                    sb.append(render(partialTemplate, context, partials))
                    i++
                }
                else -> i++ // skip orphan else/close tags
            }
        }

        return sb.toString()
    }

    private fun renderIf(
        tokens: List<Token>,
        startIndex: Int,
        context: Map<String, Any>,
        partials: Map<String, String>
    ): Pair<String, Int> {
        val ifToken = tokens[startIndex] as Token.IfOpen
        val condition = isTruthy(context[ifToken.name])

        val thenTokens = mutableListOf<Token>()
        val elseTokens = mutableListOf<Token>()
        var inElse = false
        var depth = 1
        var i = startIndex + 1

        while (i < tokens.size && depth > 0) {
            val token = tokens[i]
            when {
                token is Token.IfOpen -> {
                    depth++
                    if (inElse) elseTokens.add(token) else thenTokens.add(token)
                }
                token is Token.IfClose && depth == 1 -> {
                    depth = 0
                }
                token is Token.Else && depth == 1 -> {
                    inElse = true
                }
                else -> {
                    if (inElse) elseTokens.add(token) else thenTokens.add(token)
                }
            }
            i++
        }

        val body = if (condition) thenTokens else elseTokens
        return Pair(renderTokens(body, context, partials), i)
    }

    private fun renderEach(
        tokens: List<Token>,
        startIndex: Int,
        context: Map<String, Any>,
        partials: Map<String, String>
    ): Pair<String, Int> {
        val eachToken = tokens[startIndex] as Token.EachOpen
        val list = context[eachToken.name]

        val bodyTokens = mutableListOf<Token>()
        var depth = 1
        var i = startIndex + 1

        while (i < tokens.size && depth > 0) {
            when (val token = tokens[i]) {
                is Token.EachOpen -> {
                    depth++
                    bodyTokens.add(token)
                }
                is Token.EachClose -> {
                    depth--
                    if (depth > 0) bodyTokens.add(token)
                }
                else -> bodyTokens.add(token)
            }
            i++
        }

        val sb = StringBuilder()
        val items = when (list) {
            is List<*> -> list
            is Array<*> -> list.toList()
            else -> emptyList<Any>()
        }

        for ((index, item) in items.withIndex()) {
            val itemContext = context.toMutableMap()
            itemContext["."] = item?.toString() ?: ""
            itemContext["@index"] = index.toString()
            sb.append(renderTokens(bodyTokens, itemContext, partials))
        }

        return Pair(sb.toString(), i)
    }

    companion object {
        /**
         * Resolve a dotted variable name from context.
         * Supports "." for current item in each loops.
         */
        fun resolveValue(name: String, context: Map<String, Any>): String {
            val value = context[name]
            return when (value) {
                null -> ""
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                is List<*> -> value.joinToString(", ")
                else -> value.toString()
            }
        }

        /**
         * Check if a context value is "truthy" for conditionals.
         */
        fun isTruthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is String -> value.isNotBlank()
            is Number -> value.toDouble() != 0.0
            is List<*> -> value.isNotEmpty()
            else -> true
        }

        /**
         * Escape HTML special characters.
         */
        fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }
}
