package com.tronprotocol.app.plugins.scripting

/**
 * Tokenizer for the TronScript mini-language.
 *
 * Inspired by "Crafting Interpreters" — lexical scanning phase.
 * Produces a flat list of tokens from source text.
 */
class Lexer(private val source: String) {

    enum class TokenType {
        // Literals
        NUMBER, STRING, TRUE, FALSE,
        // Identifiers & keywords
        IDENTIFIER, LET, IF, ELSE, WHILE, FN, RETURN,
        // Operators
        PLUS, MINUS, STAR, SLASH, PERCENT,
        EQUAL, EQUAL_EQUAL, BANG, BANG_EQUAL,
        LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
        AND_AND, OR_OR,
        // Delimiters
        LPAREN, RPAREN, LBRACE, RBRACE,
        COMMA, SEMICOLON,
        // Special
        EOF
    }

    data class Token(val type: TokenType, val value: String, val position: Int)

    private var pos = 0
    private val tokens = mutableListOf<Token>()

    fun tokenize(): List<Token> {
        while (pos < source.length) {
            skipWhitespaceAndComments()
            if (pos >= source.length) break

            val ch = source[pos]
            when {
                ch.isDigit() || (ch == '.' && pos + 1 < source.length && source[pos + 1].isDigit()) -> readNumber()
                ch == '"' || ch == '\'' -> readString(ch)
                ch.isLetter() || ch == '_' -> readIdentifier()
                ch == '+' -> addToken(TokenType.PLUS, "+")
                ch == '-' -> addToken(TokenType.MINUS, "-")
                ch == '*' -> addToken(TokenType.STAR, "*")
                ch == '/' -> addToken(TokenType.SLASH, "/")
                ch == '%' -> addToken(TokenType.PERCENT, "%")
                ch == '(' -> addToken(TokenType.LPAREN, "(")
                ch == ')' -> addToken(TokenType.RPAREN, ")")
                ch == '{' -> addToken(TokenType.LBRACE, "{")
                ch == '}' -> addToken(TokenType.RBRACE, "}")
                ch == ',' -> addToken(TokenType.COMMA, ",")
                ch == ';' -> addToken(TokenType.SEMICOLON, ";")
                ch == '=' -> {
                    if (peek(1) == '=') { addToken(TokenType.EQUAL_EQUAL, "==", 2) }
                    else { addToken(TokenType.EQUAL, "=") }
                }
                ch == '!' -> {
                    if (peek(1) == '=') { addToken(TokenType.BANG_EQUAL, "!=", 2) }
                    else { addToken(TokenType.BANG, "!") }
                }
                ch == '<' -> {
                    if (peek(1) == '=') { addToken(TokenType.LESS_EQUAL, "<=", 2) }
                    else { addToken(TokenType.LESS, "<") }
                }
                ch == '>' -> {
                    if (peek(1) == '=') { addToken(TokenType.GREATER_EQUAL, ">=", 2) }
                    else { addToken(TokenType.GREATER, ">") }
                }
                ch == '&' && peek(1) == '&' -> addToken(TokenType.AND_AND, "&&", 2)
                ch == '|' && peek(1) == '|' -> addToken(TokenType.OR_OR, "||", 2)
                else -> throw ScriptError("Unexpected character '$ch' at position $pos")
            }
        }
        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    private fun peek(offset: Int): Char? =
        if (pos + offset < source.length) source[pos + offset] else null

    private fun addToken(type: TokenType, value: String, length: Int = 1) {
        tokens.add(Token(type, value, pos))
        pos += length
    }

    private fun readNumber() {
        val start = pos
        while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
        tokens.add(Token(TokenType.NUMBER, source.substring(start, pos), start))
    }

    private fun readString(quote: Char) {
        val start = pos
        pos++ // skip opening quote
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != quote) {
            if (source[pos] == '\\' && pos + 1 < source.length) {
                pos++
                when (source[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> { sb.append('\\'); sb.append(source[pos]) }
                }
            } else {
                sb.append(source[pos])
            }
            pos++
        }
        if (pos >= source.length) throw ScriptError("Unterminated string at position $start")
        pos++ // skip closing quote
        tokens.add(Token(TokenType.STRING, sb.toString(), start))
    }

    private fun readIdentifier() {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val word = source.substring(start, pos)
        val type = KEYWORDS[word] ?: TokenType.IDENTIFIER
        tokens.add(Token(type, word, start))
    }

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            when {
                source[pos].isWhitespace() -> pos++
                source[pos] == '/' && peek(1) == '/' -> {
                    // Line comment
                    while (pos < source.length && source[pos] != '\n') pos++
                }
                else -> break
            }
        }
    }

    companion object {
        private val KEYWORDS = mapOf(
            "let" to TokenType.LET,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "while" to TokenType.WHILE,
            "fn" to TokenType.FN,
            "return" to TokenType.RETURN,
            "true" to TokenType.TRUE,
            "false" to TokenType.FALSE
        )
    }
}

/** Script execution error with human-readable message. */
class ScriptError(message: String) : RuntimeException(message)
