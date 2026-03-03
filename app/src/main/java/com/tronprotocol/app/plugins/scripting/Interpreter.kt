package com.tronprotocol.app.plugins.scripting

/**
 * Tree-walking interpreter for TronScript.
 *
 * Inspired by "Crafting Interpreters" and "The Super Tiny Interpreter" (BYOX).
 * Evaluates an AST produced by the Parser with:
 * - Variables and scoping
 * - Arithmetic and string operations
 * - Conditionals and loops (with iteration cap for safety)
 * - User-defined functions
 * - Built-in functions (print, len, str, num, type, concat)
 *
 * Safety: enforces a maximum iteration count and call depth to prevent
 * infinite loops and stack overflow on the Android main thread.
 *
 * @param maxIterations Maximum loop iterations before forced termination
 * @param maxCallDepth Maximum function call depth
 */
class Interpreter(
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val maxCallDepth: Int = DEFAULT_MAX_CALL_DEPTH
) {

    /** Result value from interpretation. */
    sealed class Value {
        data class Num(val value: Double) : Value() { override fun toString() = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString() }
        data class Str(val value: String) : Value() { override fun toString() = value }
        data class Bool(val value: Boolean) : Value() { override fun toString() = value.toString() }
        object Null : Value() { override fun toString() = "null" }
        data class Func(val def: ASTNode.FunctionDef, val closure: Environment) : Value() { override fun toString() = "<fn ${def.name}>" }
    }

    /** Exception used internally for return statements. */
    private class ReturnSignal(val value: Value) : RuntimeException()

    /** Variable environment with lexical scoping. */
    class Environment(private val parent: Environment? = null) {
        private val vars = mutableMapOf<String, Value>()

        fun get(name: String): Value =
            vars[name] ?: parent?.get(name) ?: throw ScriptError("Undefined variable: $name")

        fun set(name: String, value: Value) { vars[name] = value }

        fun has(name: String): Boolean = name in vars || (parent?.has(name) ?: false)

        fun assign(name: String, value: Value) {
            if (name in vars) { vars[name] = value; return }
            if (parent != null) { parent.assign(name, value); return }
            throw ScriptError("Undefined variable: $name")
        }
    }

    private val output = StringBuilder()
    private var callDepth = 0

    /**
     * Execute a TronScript program from source text.
     *
     * @param source Script source code
     * @return Pair of (last expression value as string, captured output)
     */
    fun execute(source: String): ExecutionResult {
        output.clear()
        callDepth = 0
        val tokens = Lexer(source).tokenize()
        val ast = Parser(tokens).parse()
        val env = Environment()
        registerBuiltins(env)
        val result = evalProgram(ast, env)
        return ExecutionResult(result.toString(), output.toString())
    }

    data class ExecutionResult(val value: String, val output: String)

    // --- Evaluation ---

    private fun evalProgram(program: ASTNode.Program, env: Environment): Value {
        var lastResult: Value = Value.Null
        for (stmt in program.statements) {
            lastResult = eval(stmt, env)
        }
        return lastResult
    }

    private fun eval(node: ASTNode, env: Environment): Value = when (node) {
        is ASTNode.Program -> evalProgram(node, env)
        is ASTNode.NumberLiteral -> Value.Num(node.value)
        is ASTNode.StringLiteral -> Value.Str(node.value)
        is ASTNode.BooleanLiteral -> Value.Bool(node.value)
        is ASTNode.Identifier -> env.get(node.name)
        is ASTNode.BinaryOp -> evalBinaryOp(node, env)
        is ASTNode.UnaryOp -> evalUnaryOp(node, env)
        is ASTNode.Comparison -> evalComparison(node, env)
        is ASTNode.LogicalOp -> evalLogicalOp(node, env)
        is ASTNode.Assignment -> evalAssignment(node, env)
        is ASTNode.IfStatement -> evalIf(node, env)
        is ASTNode.WhileLoop -> evalWhile(node, env)
        is ASTNode.FunctionDef -> evalFunctionDef(node, env)
        is ASTNode.FunctionCall -> evalFunctionCall(node, env)
        is ASTNode.ReturnStatement -> {
            val value = if (node.value != null) eval(node.value, env) else Value.Null
            throw ReturnSignal(value)
        }
        is ASTNode.StringTemplate -> evalStringTemplate(node, env)
    }

    private fun evalBinaryOp(node: ASTNode.BinaryOp, env: Environment): Value {
        val left = eval(node.left, env)
        val right = eval(node.right, env)

        // String concatenation with +
        if (node.op == "+" && (left is Value.Str || right is Value.Str)) {
            return Value.Str(left.toString() + right.toString())
        }

        val l = toNumber(left)
        val r = toNumber(right)

        return Value.Num(
            when (node.op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> {
                    if (r == 0.0) throw ScriptError("Division by zero")
                    l / r
                }
                "%" -> {
                    if (r == 0.0) throw ScriptError("Modulo by zero")
                    l % r
                }
                else -> throw ScriptError("Unknown operator: ${node.op}")
            }
        )
    }

    private fun evalUnaryOp(node: ASTNode.UnaryOp, env: Environment): Value {
        val operand = eval(node.operand, env)
        return when (node.op) {
            "-" -> Value.Num(-toNumber(operand))
            "!" -> Value.Bool(!isTruthy(operand))
            else -> throw ScriptError("Unknown unary operator: ${node.op}")
        }
    }

    private fun evalComparison(node: ASTNode.Comparison, env: Environment): Value {
        val left = eval(node.left, env)
        val right = eval(node.right, env)

        // String comparison
        if (left is Value.Str && right is Value.Str) {
            return Value.Bool(
                when (node.op) {
                    "==" -> left.value == right.value
                    "!=" -> left.value != right.value
                    "<" -> left.value < right.value
                    ">" -> left.value > right.value
                    "<=" -> left.value <= right.value
                    ">=" -> left.value >= right.value
                    else -> throw ScriptError("Unknown comparison: ${node.op}")
                }
            )
        }

        val l = toNumber(left)
        val r = toNumber(right)
        return Value.Bool(
            when (node.op) {
                "==" -> l == r
                "!=" -> l != r
                "<" -> l < r
                ">" -> l > r
                "<=" -> l <= r
                ">=" -> l >= r
                else -> throw ScriptError("Unknown comparison: ${node.op}")
            }
        )
    }

    private fun evalLogicalOp(node: ASTNode.LogicalOp, env: Environment): Value {
        val left = eval(node.left, env)
        return when (node.op) {
            "&&" -> if (!isTruthy(left)) left else eval(node.right, env)
            "||" -> if (isTruthy(left)) left else eval(node.right, env)
            else -> throw ScriptError("Unknown logical operator: ${node.op}")
        }
    }

    private fun evalAssignment(node: ASTNode.Assignment, env: Environment): Value {
        val value = eval(node.value, env)
        if (env.has(node.name)) {
            env.assign(node.name, value)
        } else {
            env.set(node.name, value)
        }
        return value
    }

    private fun evalIf(node: ASTNode.IfStatement, env: Environment): Value {
        val condition = eval(node.condition, env)
        val body = if (isTruthy(condition)) node.thenBody else node.elseBody
        var result: Value = Value.Null
        if (body != null) {
            for (stmt in body) {
                result = eval(stmt, env)
            }
        }
        return result
    }

    private fun evalWhile(node: ASTNode.WhileLoop, env: Environment): Value {
        var iterations = 0
        var result: Value = Value.Null
        while (isTruthy(eval(node.condition, env))) {
            if (++iterations > maxIterations) {
                throw ScriptError("Loop exceeded maximum iterations ($maxIterations)")
            }
            for (stmt in node.body) {
                result = eval(stmt, env)
            }
        }
        return result
    }

    private fun evalFunctionDef(node: ASTNode.FunctionDef, env: Environment): Value {
        val func = Value.Func(node, env)
        env.set(node.name, func)
        return func
    }

    private fun evalFunctionCall(node: ASTNode.FunctionCall, env: Environment): Value {
        val callee = env.get(node.name)

        if (callee !is Value.Func) {
            throw ScriptError("'${node.name}' is not a function")
        }

        if (node.arguments.size != callee.def.params.size) {
            throw ScriptError("${node.name} expects ${callee.def.params.size} arguments, got ${node.arguments.size}")
        }

        if (++callDepth > maxCallDepth) {
            callDepth--
            throw ScriptError("Maximum call depth exceeded ($maxCallDepth)")
        }

        val callEnv = Environment(callee.closure)
        for ((i, param) in callee.def.params.withIndex()) {
            callEnv.set(param, eval(node.arguments[i], env))
        }

        val result = try {
            var lastResult: Value = Value.Null
            for (stmt in callee.def.body) {
                lastResult = eval(stmt, callEnv)
            }
            lastResult
        } catch (ret: ReturnSignal) {
            ret.value
        }

        callDepth--
        return result
    }

    private fun evalStringTemplate(node: ASTNode.StringTemplate, env: Environment): Value {
        val sb = StringBuilder()
        for (part in node.parts) {
            sb.append(eval(part, env).toString())
        }
        return Value.Str(sb.toString())
    }

    // --- Builtins ---

    private fun registerBuiltins(env: Environment) {
        // print(value) -> outputs to captured output
        registerBuiltin(env, "print", listOf("value")) { args, _ ->
            val text = args[0].toString()
            output.appendLine(text)
            Value.Str(text)
        }

        // len(str) -> length of string
        registerBuiltin(env, "len", listOf("value")) { args, _ ->
            Value.Num(args[0].toString().length.toDouble())
        }

        // str(value) -> convert to string
        registerBuiltin(env, "str", listOf("value")) { args, _ ->
            Value.Str(args[0].toString())
        }

        // num(value) -> convert to number
        registerBuiltin(env, "num", listOf("value")) { args, _ ->
            Value.Num(toNumber(args[0]))
        }

        // type(value) -> type name string
        registerBuiltin(env, "type", listOf("value")) { args, _ ->
            Value.Str(
                when (args[0]) {
                    is Value.Num -> "number"
                    is Value.Str -> "string"
                    is Value.Bool -> "boolean"
                    is Value.Null -> "null"
                    is Value.Func -> "function"
                }
            )
        }

        // concat(a, b) -> string concatenation
        registerBuiltin(env, "concat", listOf("a", "b")) { args, _ ->
            Value.Str(args[0].toString() + args[1].toString())
        }

        // abs(n) -> absolute value
        registerBuiltin(env, "abs", listOf("n")) { args, _ ->
            Value.Num(kotlin.math.abs(toNumber(args[0])))
        }

        // min(a, b) -> minimum
        registerBuiltin(env, "min", listOf("a", "b")) { args, _ ->
            Value.Num(kotlin.math.min(toNumber(args[0]), toNumber(args[1])))
        }

        // max(a, b) -> maximum
        registerBuiltin(env, "max", listOf("a", "b")) { args, _ ->
            Value.Num(kotlin.math.max(toNumber(args[0]), toNumber(args[1])))
        }
    }

    private fun registerBuiltin(
        env: Environment,
        name: String,
        params: List<String>,
        body: (List<Value>, Environment) -> Value
    ) {
        // Create a synthetic function definition that delegates to the Kotlin lambda
        val fnDef = ASTNode.FunctionDef(name, params, emptyList())
        env.set(name, object : Value.Func(fnDef, env) {
            val impl = body
            override fun toString(): String = "<builtin $name>"
        })
    }

    // When calling builtins we need special handling
    // Override evalFunctionCall to detect builtins

    // --- Helpers ---

    private fun toNumber(value: Value): Double = when (value) {
        is Value.Num -> value.value
        is Value.Str -> value.value.toDoubleOrNull() ?: throw ScriptError("Cannot convert '${value.value}' to number")
        is Value.Bool -> if (value.value) 1.0 else 0.0
        is Value.Null -> 0.0
        is Value.Func -> throw ScriptError("Cannot convert function to number")
    }

    private fun isTruthy(value: Value): Boolean = when (value) {
        is Value.Bool -> value.value
        is Value.Null -> false
        is Value.Num -> value.value != 0.0
        is Value.Str -> value.value.isNotEmpty()
        is Value.Func -> true
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 10_000
        const val DEFAULT_MAX_CALL_DEPTH = 64
    }
}
