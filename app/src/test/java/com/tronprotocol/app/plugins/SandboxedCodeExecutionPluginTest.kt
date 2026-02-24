package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SandboxedCodeExecutionPluginTest {

    private lateinit var plugin: SandboxedCodeExecutionPlugin

    @Before
    fun setUp() {
        plugin = SandboxedCodeExecutionPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    // --- calc command ---

    @Test
    fun calc_evaluatesBasicArithmetic() {
        val result = plugin.execute("calc|2+3")
        assertTrue("calc should succeed", result.isSuccess)
        assertEquals("5.0", result.data)
    }

    @Test
    fun calc_evaluatesWithParentheses() {
        val result = plugin.execute("calc|(2+3)*4")
        assertTrue("calc with parentheses should succeed", result.isSuccess)
        assertEquals("20.0", result.data)
    }

    @Test
    fun calc_evaluatesSubtraction() {
        val result = plugin.execute("calc|10-3")
        assertTrue(result.isSuccess)
        assertEquals("7.0", result.data)
    }

    @Test
    fun calc_evaluatesDivision() {
        val result = plugin.execute("calc|20/5")
        assertTrue(result.isSuccess)
        assertEquals("4.0", result.data)
    }

    @Test
    fun calc_rejectsNonMathCharacters() {
        val result = plugin.execute("calc|abc+3")
        assertFalse("calc should reject non-math characters", result.isSuccess)
    }

    // --- json_get command ---

    @Test
    fun jsonGet_extractsFieldFromJson() {
        val result = plugin.execute("json_get|{\"name\":\"Alice\",\"age\":30}|name")
        assertTrue("json_get should succeed", result.isSuccess)
        assertEquals("Alice", result.data)
    }

    @Test
    fun jsonGet_extractsNumericField() {
        val result = plugin.execute("json_get|{\"name\":\"Alice\",\"age\":30}|age")
        assertTrue(result.isSuccess)
        assertEquals("30", result.data)
    }

    @Test
    fun jsonGet_returnsErrorForMissingField() {
        val result = plugin.execute("json_get|{\"name\":\"Alice\"}|email")
        assertFalse("json_get should fail for missing field", result.isSuccess)
        assertTrue(
            "Error should mention field not found",
            result.errorMessage?.contains("Field not found") == true
        )
    }

    // --- b64_encode command ---

    @Test
    fun b64Encode_encodesTextCorrectly() {
        val result = plugin.execute("b64_encode|Hello World")
        assertTrue("b64_encode should succeed", result.isSuccess)
        assertEquals("SGVsbG8gV29ybGQ=", result.data)
    }

    // --- b64_decode command ---

    @Test
    fun b64Decode_decodesCorrectly() {
        val result = plugin.execute("b64_decode|SGVsbG8gV29ybGQ=")
        assertTrue("b64_decode should succeed", result.isSuccess)
        assertEquals("Hello World", result.data)
    }

    // --- b64 round-trip ---

    @Test
    fun b64_roundTripPreservesText() {
        val original = "TronProtocol test data 12345!@#"
        val encodeResult = plugin.execute("b64_encode|$original")
        assertTrue(encodeResult.isSuccess)

        val decodeResult = plugin.execute("b64_decode|${encodeResult.data}")
        assertTrue(decodeResult.isSuccess)
        assertEquals("Round-trip should preserve original text", original, decodeResult.data)
    }

    // --- upper command ---

    @Test
    fun upper_convertsToUppercase() {
        val result = plugin.execute("upper|hello world")
        assertTrue(result.isSuccess)
        assertEquals("HELLO WORLD", result.data)
    }

    @Test
    fun upper_alreadyUppercaseUnchanged() {
        val result = plugin.execute("upper|HELLO")
        assertTrue(result.isSuccess)
        assertEquals("HELLO", result.data)
    }

    // --- lower command ---

    @Test
    fun lower_convertsToLowercase() {
        val result = plugin.execute("lower|HELLO WORLD")
        assertTrue(result.isSuccess)
        assertEquals("hello world", result.data)
    }

    @Test
    fun lower_alreadyLowercaseUnchanged() {
        val result = plugin.execute("lower|hello")
        assertTrue(result.isSuccess)
        assertEquals("hello", result.data)
    }

    // --- Error cases ---

    @Test
    fun execute_emptyInputReturnsError() {
        val result = plugin.execute("")
        assertFalse("Empty input should return error", result.isSuccess)
    }

    @Test
    fun execute_inputTooLargeReturnsError() {
        val largeInput = "calc|" + "1+".repeat(2001) + "1"
        val result = plugin.execute(largeInput)
        assertFalse("Input exceeding 4000 chars should return error", result.isSuccess)
        assertTrue(
            "Error should mention input too large",
            result.errorMessage?.contains("too large") == true
        )
    }

    @Test
    fun execute_unknownCommandReturnsError() {
        val result = plugin.execute("foobar|some data")
        assertFalse("Unknown command should return error", result.isSuccess)
        assertTrue(
            "Error should mention unknown command",
            result.errorMessage?.contains("Unknown command") == true
        )
    }

    // --- Plugin interface properties ---

    @Test
    fun pluginId_isSandboxExec() {
        assertEquals("sandbox_exec", plugin.id)
    }

    @Test
    fun pluginName_isSandboxExec() {
        assertEquals("Sandbox Exec", plugin.name)
    }

    @Test
    fun pluginIsEnabled_defaultTrue() {
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun pluginDescription_isNotEmpty() {
        assertTrue(plugin.description.isNotEmpty())
    }
}
