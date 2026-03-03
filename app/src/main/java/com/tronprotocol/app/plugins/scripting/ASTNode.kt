package com.tronprotocol.app.plugins.scripting

/**
 * Abstract Syntax Tree nodes for the TronScript interpreter.
 *
 * Inspired by "Crafting Interpreters" (BYOX Programming Language tutorials).
 * Uses a sealed class hierarchy for exhaustive matching.
 */
sealed class ASTNode {

    /** A program is a sequence of statements. */
    data class Program(val statements: List<ASTNode>) : ASTNode()

    // --- Expressions ---

    data class NumberLiteral(val value: Double) : ASTNode()
    data class StringLiteral(val value: String) : ASTNode()
    data class BooleanLiteral(val value: Boolean) : ASTNode()
    data class Identifier(val name: String) : ASTNode()

    data class BinaryOp(val left: ASTNode, val op: String, val right: ASTNode) : ASTNode()
    data class UnaryOp(val op: String, val operand: ASTNode) : ASTNode()
    data class Comparison(val left: ASTNode, val op: String, val right: ASTNode) : ASTNode()
    data class LogicalOp(val left: ASTNode, val op: String, val right: ASTNode) : ASTNode()

    /** Function call: name(arg1, arg2, ...) */
    data class FunctionCall(val name: String, val arguments: List<ASTNode>) : ASTNode()

    // --- Statements ---

    /** Variable assignment: let name = expr  OR  name = expr */
    data class Assignment(val name: String, val value: ASTNode) : ASTNode()

    /** If statement: if (condition) { body } else { elseBody } */
    data class IfStatement(
        val condition: ASTNode,
        val thenBody: List<ASTNode>,
        val elseBody: List<ASTNode>?
    ) : ASTNode()

    /** While loop: while (condition) { body } */
    data class WhileLoop(val condition: ASTNode, val body: List<ASTNode>) : ASTNode()

    /** Function definition: fn name(params) { body } */
    data class FunctionDef(
        val name: String,
        val params: List<String>,
        val body: List<ASTNode>
    ) : ASTNode()

    /** Return statement: return expr */
    data class ReturnStatement(val value: ASTNode?) : ASTNode()

    /** String interpolation within template literals */
    data class StringTemplate(val parts: List<ASTNode>) : ASTNode()
}
