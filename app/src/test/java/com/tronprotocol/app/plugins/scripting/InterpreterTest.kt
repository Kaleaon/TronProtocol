package com.tronprotocol.app.plugins.scripting

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InterpreterTest {

    private lateinit var interpreter: Interpreter

    @Before
    fun setUp() {
        interpreter = Interpreter(maxIterations = 1000, maxCallDepth = 32)
    }

    // --- Arithmetic ---

    @Test
    fun `basic arithmetic`() {
        assertEquals("7", interpreter.execute("3 + 4").value)
        assertEquals("6", interpreter.execute("10 - 4").value)
        assertEquals("12", interpreter.execute("3 * 4").value)
        assertEquals("2.5", interpreter.execute("5 / 2").value)
    }

    @Test
    fun `operator precedence`() {
        assertEquals("14", interpreter.execute("2 + 3 * 4").value)
        assertEquals("20", interpreter.execute("(2 + 3) * 4").value)
    }

    @Test
    fun `modulo`() {
        assertEquals("1", interpreter.execute("10 % 3").value)
    }

    @Test
    fun `unary negation`() {
        assertEquals("-5", interpreter.execute("-5").value)
        assertEquals("5", interpreter.execute("-(-5)").value)
    }

    // --- Variables ---

    @Test
    fun `variable assignment and read`() {
        val result = interpreter.execute("""
            let x = 42;
            x
        """)
        assertEquals("42", result.value)
    }

    @Test
    fun `variable reassignment`() {
        val result = interpreter.execute("""
            let x = 1;
            x = 2;
            x
        """)
        assertEquals("2", result.value)
    }

    // --- Strings ---

    @Test
    fun `string literals`() {
        assertEquals("hello", interpreter.execute("\"hello\"").value)
    }

    @Test
    fun `string concatenation`() {
        assertEquals("hello world", interpreter.execute("\"hello\" + \" \" + \"world\"").value)
    }

    @Test
    fun `string comparison`() {
        assertEquals("true", interpreter.execute("\"abc\" == \"abc\"").value)
        assertEquals("false", interpreter.execute("\"abc\" == \"def\"").value)
    }

    // --- Booleans ---

    @Test
    fun `boolean literals`() {
        assertEquals("true", interpreter.execute("true").value)
        assertEquals("false", interpreter.execute("false").value)
    }

    @Test
    fun `logical operators`() {
        assertEquals("true", interpreter.execute("true && true").value)
        assertEquals("false", interpreter.execute("true && false").value)
        assertEquals("true", interpreter.execute("false || true").value)
        assertEquals("false", interpreter.execute("false || false").value)
    }

    @Test
    fun `not operator`() {
        assertEquals("true", interpreter.execute("!false").value)
        assertEquals("false", interpreter.execute("!true").value)
    }

    // --- Comparisons ---

    @Test
    fun `numeric comparisons`() {
        assertEquals("true", interpreter.execute("5 > 3").value)
        assertEquals("false", interpreter.execute("5 < 3").value)
        assertEquals("true", interpreter.execute("5 >= 5").value)
        assertEquals("true", interpreter.execute("3 <= 5").value)
        assertEquals("true", interpreter.execute("5 == 5").value)
        assertEquals("true", interpreter.execute("5 != 3").value)
    }

    // --- If/Else ---

    @Test
    fun `if statement true branch`() {
        val result = interpreter.execute("""
            let x = 10;
            if (x > 5) {
                x = 100;
            }
            x
        """)
        assertEquals("100", result.value)
    }

    @Test
    fun `if-else statement`() {
        val result = interpreter.execute("""
            let x = 3;
            let result = 0;
            if (x > 5) {
                result = "big";
            } else {
                result = "small";
            }
            result
        """)
        assertEquals("small", result.value)
    }

    // --- While loops ---

    @Test
    fun `while loop`() {
        val result = interpreter.execute("""
            let sum = 0;
            let i = 1;
            while (i <= 5) {
                sum = sum + i;
                i = i + 1;
            }
            sum
        """)
        assertEquals("15", result.value)
    }

    @Test(expected = ScriptError::class)
    fun `while loop respects iteration limit`() {
        interpreter.execute("""
            let i = 0;
            while (true) {
                i = i + 1;
            }
        """)
    }

    // --- Functions ---

    @Test
    fun `function definition and call`() {
        val result = interpreter.execute("""
            fn add(a, b) {
                return a + b;
            }
            add(3, 4)
        """)
        assertEquals("7", result.value)
    }

    @Test
    fun `recursive function`() {
        val result = interpreter.execute("""
            fn factorial(n) {
                if (n <= 1) { return 1; }
                return n * factorial(n - 1);
            }
            factorial(5)
        """)
        assertEquals("120", result.value)
    }

    @Test(expected = ScriptError::class)
    fun `call depth limit`() {
        interpreter.execute("""
            fn infinite(n) {
                return infinite(n + 1);
            }
            infinite(0)
        """)
    }

    @Test
    fun `fibonacci function`() {
        val result = interpreter.execute("""
            fn fib(n) {
                if (n <= 1) { return n; }
                return fib(n - 1) + fib(n - 2);
            }
            fib(10)
        """)
        assertEquals("55", result.value)
    }

    // --- Builtins ---

    @Test
    fun `print captures output`() {
        val result = interpreter.execute("""
            print("hello");
            print("world");
        """)
        assertEquals("hello\nworld\n", result.output)
    }

    @Test
    fun `len function`() {
        assertEquals("5", interpreter.execute("len(\"hello\")").value)
    }

    @Test
    fun `type function`() {
        assertEquals("number", interpreter.execute("type(42)").value)
        assertEquals("string", interpreter.execute("type(\"hi\")").value)
        assertEquals("boolean", interpreter.execute("type(true)").value)
    }

    @Test
    fun `abs function`() {
        assertEquals("5", interpreter.execute("abs(-5)").value)
        assertEquals("5", interpreter.execute("abs(5)").value)
    }

    @Test
    fun `min and max functions`() {
        assertEquals("3", interpreter.execute("min(3, 7)").value)
        assertEquals("7", interpreter.execute("max(3, 7)").value)
    }

    // --- Error handling ---

    @Test(expected = ScriptError::class)
    fun `undefined variable throws`() {
        interpreter.execute("x")
    }

    @Test(expected = ScriptError::class)
    fun `division by zero throws`() {
        interpreter.execute("5 / 0")
    }

    @Test(expected = ScriptError::class)
    fun `wrong argument count throws`() {
        interpreter.execute("""
            fn f(x) { return x; }
            f(1, 2)
        """)
    }

    // --- Comments ---

    @Test
    fun `line comments are ignored`() {
        val result = interpreter.execute("""
            // This is a comment
            let x = 42; // inline comment
            x
        """)
        assertEquals("42", result.value)
    }

    // --- Complex programs ---

    @Test
    fun `fizzbuzz`() {
        val result = interpreter.execute("""
            let output = "";
            let i = 1;
            while (i <= 15) {
                if (i % 15 == 0) {
                    output = output + "FizzBuzz ";
                } else if (i % 3 == 0) {
                    output = output + "Fizz ";
                } else if (i % 5 == 0) {
                    output = output + "Buzz ";
                } else {
                    output = output + str(i) + " ";
                }
                i = i + 1;
            }
            output
        """)
        assertEquals("1 2 Fizz 4 Buzz Fizz 7 8 Fizz Buzz 11 Fizz 13 14 FizzBuzz ", result.value)
    }
}
