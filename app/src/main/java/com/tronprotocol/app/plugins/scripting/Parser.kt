package com.tronprotocol.app.plugins.scripting

import com.tronprotocol.app.plugins.scripting.Lexer.TokenType

/**
 * Recursive descent parser for TronScript.
 *
 * Inspired by "Crafting Interpreters" — Pratt parsing with precedence climbing.
 * Produces an AST from a token list.
 *
 * Grammar (simplified):
 *   program    -> statement* EOF
 *   statement  -> letStmt | ifStmt | whileStmt | fnDef | returnStmt | exprStmt
 *   letStmt    -> "let" IDENTIFIER "=" expression ";"
 *   ifStmt     -> "if" "(" expression ")" block ("else" block)?
 *   whileStmt  -> "while" "(" expression ")" block
 *   fnDef      -> "fn" IDENTIFIER "(" params? ")" block
 *   returnStmt -> "return" expression? ";"
 *   exprStmt   -> expression ";" | assignment ";"
 *   block      -> "{" statement* "}"
 *   expression -> logic_or
 *   logic_or   -> logic_and ("||" logic_and)*
 *   logic_and  -> comparison ("&&" comparison)*
 *   comparison -> addition (("<" | ">" | "<=" | ">=" | "==" | "!=") addition)?
 *   addition   -> multiplication (("+" | "-") multiplication)*
 *   multiplication -> unary (("*" | "/" | "%") unary)*
 *   unary      -> ("!" | "-") unary | call
 *   call       -> primary ("(" arguments? ")")?
 *   primary    -> NUMBER | STRING | TRUE | FALSE | IDENTIFIER | "(" expression ")"
 */
class Parser(private val tokens: List<Lexer.Token>) {

    private var pos = 0

    fun parse(): ASTNode.Program {
        val statements = mutableListOf<ASTNode>()
        while (!isAtEnd()) {
            statements.add(parseStatement())
        }
        return ASTNode.Program(statements)
    }

    private fun parseStatement(): ASTNode {
        return when (current().type) {
            TokenType.LET -> parseLetStatement()
            TokenType.IF -> parseIfStatement()
            TokenType.WHILE -> parseWhileStatement()
            TokenType.FN -> parseFunctionDef()
            TokenType.RETURN -> parseReturnStatement()
            else -> parseExpressionOrAssignment()
        }
    }

    private fun parseLetStatement(): ASTNode {
        advance() // skip "let"
        val name = expect(TokenType.IDENTIFIER, "Expected variable name").value
        expect(TokenType.EQUAL, "Expected '=' after variable name")
        val value = parseExpression()
        consumeOptionalSemicolon()
        return ASTNode.Assignment(name, value)
    }

    private fun parseIfStatement(): ASTNode {
        advance() // skip "if"
        expect(TokenType.LPAREN, "Expected '(' after 'if'")
        val condition = parseExpression()
        expect(TokenType.RPAREN, "Expected ')' after condition")
        val thenBody = parseBlock()
        val elseBody = if (check(TokenType.ELSE)) {
            advance() // skip "else"
            if (check(TokenType.IF)) {
                listOf(parseIfStatement()) // else if
            } else {
                parseBlock()
            }
        } else null
        return ASTNode.IfStatement(condition, thenBody, elseBody)
    }

    private fun parseWhileStatement(): ASTNode {
        advance() // skip "while"
        expect(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        expect(TokenType.RPAREN, "Expected ')' after condition")
        val body = parseBlock()
        return ASTNode.WhileLoop(condition, body)
    }

    private fun parseFunctionDef(): ASTNode {
        advance() // skip "fn"
        val name = expect(TokenType.IDENTIFIER, "Expected function name").value
        expect(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            params.add(expect(TokenType.IDENTIFIER, "Expected parameter name").value)
            while (check(TokenType.COMMA)) {
                advance()
                params.add(expect(TokenType.IDENTIFIER, "Expected parameter name").value)
            }
        }
        expect(TokenType.RPAREN, "Expected ')' after parameters")
        val body = parseBlock()
        return ASTNode.FunctionDef(name, params, body)
    }

    private fun parseReturnStatement(): ASTNode {
        advance() // skip "return"
        val value = if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) {
            parseExpression()
        } else null
        consumeOptionalSemicolon()
        return ASTNode.ReturnStatement(value)
    }

    private fun parseExpressionOrAssignment(): ASTNode {
        val expr = parseExpression()

        // Check for assignment: identifier = expression
        if (expr is ASTNode.Identifier && check(TokenType.EQUAL)) {
            advance() // skip "="
            val value = parseExpression()
            consumeOptionalSemicolon()
            return ASTNode.Assignment(expr.name, value)
        }

        consumeOptionalSemicolon()
        return expr
    }

    private fun parseBlock(): List<ASTNode> {
        expect(TokenType.LBRACE, "Expected '{'")
        val statements = mutableListOf<ASTNode>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            statements.add(parseStatement())
        }
        expect(TokenType.RBRACE, "Expected '}'")
        return statements
    }

    // --- Expression parsing with precedence climbing ---

    private fun parseExpression(): ASTNode = parseLogicOr()

    private fun parseLogicOr(): ASTNode {
        var left = parseLogicAnd()
        while (check(TokenType.OR_OR)) {
            advance()
            val right = parseLogicAnd()
            left = ASTNode.LogicalOp(left, "||", right)
        }
        return left
    }

    private fun parseLogicAnd(): ASTNode {
        var left = parseComparison()
        while (check(TokenType.AND_AND)) {
            advance()
            val right = parseComparison()
            left = ASTNode.LogicalOp(left, "&&", right)
        }
        return left
    }

    private fun parseComparison(): ASTNode {
        var left = parseAddition()
        val compOps = setOf(
            TokenType.LESS, TokenType.LESS_EQUAL,
            TokenType.GREATER, TokenType.GREATER_EQUAL,
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL
        )
        if (current().type in compOps) {
            val op = current().value
            advance()
            val right = parseAddition()
            left = ASTNode.Comparison(left, op, right)
        }
        return left
    }

    private fun parseAddition(): ASTNode {
        var left = parseMultiplication()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = current().value
            advance()
            val right = parseMultiplication()
            left = ASTNode.BinaryOp(left, op, right)
        }
        return left
    }

    private fun parseMultiplication(): ASTNode {
        var left = parseUnary()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            val op = current().value
            advance()
            val right = parseUnary()
            left = ASTNode.BinaryOp(left, op, right)
        }
        return left
    }

    private fun parseUnary(): ASTNode {
        if (check(TokenType.BANG)) {
            advance()
            return ASTNode.UnaryOp("!", parseUnary())
        }
        if (check(TokenType.MINUS)) {
            advance()
            return ASTNode.UnaryOp("-", parseUnary())
        }
        return parseCall()
    }

    private fun parseCall(): ASTNode {
        val expr = parsePrimary()
        if (expr is ASTNode.Identifier && check(TokenType.LPAREN)) {
            advance() // skip "("
            val args = mutableListOf<ASTNode>()
            if (!check(TokenType.RPAREN)) {
                args.add(parseExpression())
                while (check(TokenType.COMMA)) {
                    advance()
                    args.add(parseExpression())
                }
            }
            expect(TokenType.RPAREN, "Expected ')' after arguments")
            return ASTNode.FunctionCall(expr.name, args)
        }
        return expr
    }

    private fun parsePrimary(): ASTNode {
        val token = current()
        return when (token.type) {
            TokenType.NUMBER -> { advance(); ASTNode.NumberLiteral(token.value.toDouble()) }
            TokenType.STRING -> { advance(); ASTNode.StringLiteral(token.value) }
            TokenType.TRUE -> { advance(); ASTNode.BooleanLiteral(true) }
            TokenType.FALSE -> { advance(); ASTNode.BooleanLiteral(false) }
            TokenType.IDENTIFIER -> { advance(); ASTNode.Identifier(token.value) }
            TokenType.LPAREN -> {
                advance()
                val expr = parseExpression()
                expect(TokenType.RPAREN, "Expected ')'")
                expr
            }
            else -> throw ScriptError("Unexpected token '${token.value}' at position ${token.position}")
        }
    }

    // --- Helpers ---

    private fun current(): Lexer.Token = tokens[pos]
    private fun isAtEnd(): Boolean = current().type == TokenType.EOF
    private fun check(type: TokenType): Boolean = !isAtEnd() && current().type == type
    private fun advance(): Lexer.Token = tokens[pos++]

    private fun expect(type: TokenType, message: String): Lexer.Token {
        if (!check(type)) {
            throw ScriptError("$message, got '${current().value}' at position ${current().position}")
        }
        return advance()
    }

    private fun consumeOptionalSemicolon() {
        if (check(TokenType.SEMICOLON)) advance()
    }
}
