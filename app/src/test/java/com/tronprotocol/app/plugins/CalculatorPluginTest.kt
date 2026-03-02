package com.tronprotocol.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CalculatorPluginTest {

    private lateinit var plugin: CalculatorPlugin

    @Before
    fun setUp() {
        plugin = CalculatorPlugin()
    }

    // --- Basic Arithmetic ---

    @Test
    fun testAddition() {
        assertEquals(5.0, plugin.evaluateExpression("2 + 3"), 0.001)
    }

    @Test
    fun testSubtraction() {
        assertEquals(7.0, plugin.evaluateExpression("10 - 3"), 0.001)
    }

    @Test
    fun testMultiplication() {
        assertEquals(15.0, plugin.evaluateExpression("3 * 5"), 0.001)
    }

    @Test
    fun testDivision() {
        assertEquals(4.0, plugin.evaluateExpression("20 / 5"), 0.001)
    }

    @Test
    fun testModulo() {
        assertEquals(1.0, plugin.evaluateExpression("7 % 3"), 0.001)
    }

    // --- Operator Precedence ---

    @Test
    fun testMultiplicationBeforeAddition() {
        assertEquals(14.0, plugin.evaluateExpression("2 + 3 * 4"), 0.001)
    }

    @Test
    fun testDivisionBeforeSubtraction() {
        assertEquals(8.0, plugin.evaluateExpression("10 - 4 / 2"), 0.001)
    }

    @Test
    fun testParenthesesOverridePrecedence() {
        assertEquals(20.0, plugin.evaluateExpression("(2 + 3) * 4"), 0.001)
    }

    @Test
    fun testNestedParentheses() {
        assertEquals(14.0, plugin.evaluateExpression("2 * (3 + (1 + 3))"), 0.001)
    }

    @Test
    fun testComplexExpression() {
        // 2 + 3 * 4 - 6 / 2 = 2 + 12 - 3 = 11
        assertEquals(11.0, plugin.evaluateExpression("2 + 3 * 4 - 6 / 2"), 0.001)
    }

    // --- Exponentiation ---

    @Test
    fun testPower() {
        assertEquals(8.0, plugin.evaluateExpression("2^3"), 0.001)
    }

    @Test
    fun testPowerRightAssociative() {
        // 2^3^2 = 2^(3^2) = 2^9 = 512 (right-associative)
        assertEquals(512.0, plugin.evaluateExpression("2^3^2"), 0.001)
    }

    @Test
    fun testPowerBeforeMultiplication() {
        // 3 * 2^3 = 3 * 8 = 24
        assertEquals(24.0, plugin.evaluateExpression("3 * 2^3"), 0.001)
    }

    // --- Unary Minus ---

    @Test
    fun testNegativeNumber() {
        assertEquals(-5.0, plugin.evaluateExpression("-5"), 0.001)
    }

    @Test
    fun testNegativeInExpression() {
        assertEquals(-1.0, plugin.evaluateExpression("2 + -3"), 0.001)
    }

    @Test
    fun testDoubleNegative() {
        assertEquals(5.0, plugin.evaluateExpression("--5"), 0.001)
    }

    // --- Functions ---

    @Test
    fun testSqrt() {
        assertEquals(3.0, plugin.evaluateExpression("sqrt(9)"), 0.001)
    }

    @Test
    fun testSin() {
        assertEquals(1.0, plugin.evaluateExpression("sin(90)"), 0.001)
    }

    @Test
    fun testCos() {
        assertEquals(1.0, plugin.evaluateExpression("cos(0)"), 0.001)
    }

    @Test
    fun testLog() {
        assertEquals(2.0, plugin.evaluateExpression("log(100)"), 0.001)
    }

    @Test
    fun testLn() {
        assertEquals(1.0, plugin.evaluateExpression("ln(2.718281828)"), 0.001)
    }

    @Test
    fun testAbs() {
        assertEquals(5.0, plugin.evaluateExpression("abs(-5)"), 0.001)
    }

    @Test
    fun testCeil() {
        assertEquals(4.0, plugin.evaluateExpression("ceil(3.2)"), 0.001)
    }

    @Test
    fun testFloor() {
        assertEquals(3.0, plugin.evaluateExpression("floor(3.9)"), 0.001)
    }

    @Test
    fun testRound() {
        assertEquals(4.0, plugin.evaluateExpression("round(3.7)"), 0.001)
    }

    @Test
    fun testMax() {
        assertEquals(7.0, plugin.evaluateExpression("max(3, 7)"), 0.001)
    }

    @Test
    fun testMin() {
        assertEquals(3.0, plugin.evaluateExpression("min(3, 7)"), 0.001)
    }

    @Test
    fun testNestedFunctions() {
        assertEquals(3.0, plugin.evaluateExpression("sqrt(abs(-9))"), 0.001)
    }

    @Test
    fun testFunctionWithExpression() {
        assertEquals(5.0, plugin.evaluateExpression("sqrt(20 + 5)"), 0.001)
    }

    // --- Constants ---

    @Test
    fun testPi() {
        assertEquals(Math.PI, plugin.evaluateExpression("pi"), 0.001)
    }

    @Test
    fun testEuler() {
        assertEquals(Math.E, plugin.evaluateExpression("e"), 0.001)
    }

    @Test
    fun testPiInExpression() {
        assertEquals(Math.PI * 2, plugin.evaluateExpression("2 * pi"), 0.001)
    }

    // --- Decimal Numbers ---

    @Test
    fun testDecimalArithmetic() {
        assertEquals(3.7, plugin.evaluateExpression("1.5 + 2.2"), 0.001)
    }

    // --- Unit Conversions ---

    @Test
    fun testCelsiusToFahrenheit() {
        assertEquals(212.0, plugin.handleUnitConversion("100 c to f"), 0.001)
    }

    @Test
    fun testFahrenheitToCelsius() {
        assertEquals(0.0, plugin.handleUnitConversion("32 f to c"), 0.001)
    }

    @Test
    fun testCelsiusToKelvin() {
        assertEquals(373.15, plugin.handleUnitConversion("100 c to k"), 0.001)
    }

    @Test
    fun testKilometersToMiles() {
        assertEquals(0.621371, plugin.handleUnitConversion("1 km to mi"), 0.001)
    }

    @Test
    fun testFeetToMeters() {
        assertEquals(0.3048, plugin.handleUnitConversion("1 ft to m"), 0.001)
    }

    @Test
    fun testPoundsToKilograms() {
        assertEquals(0.453592, plugin.handleUnitConversion("1 lb to kg"), 0.001)
    }

    @Test
    fun testKilogramsToOunces() {
        assertEquals(35.274, plugin.handleUnitConversion("1 kg to oz"), 0.01)
    }

    @Test
    fun testHoursToSeconds() {
        assertEquals(3600.0, plugin.handleUnitConversion("1 h to s"), 0.001)
    }

    @Test
    fun testGigabytesToMegabytes() {
        assertEquals(1024.0, plugin.handleUnitConversion("1 gb to mb"), 0.001)
    }

    // --- Error Cases ---

    @Test
    fun testDivisionByZero() {
        try {
            plugin.evaluateExpression("5 / 0")
            fail("Should throw exception for division by zero")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Division by zero"))
        }
    }

    @Test
    fun testSqrtNegative() {
        try {
            plugin.evaluateExpression("sqrt(-4)")
            fail("Should throw exception for sqrt of negative")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("negative"))
        }
    }

    @Test
    fun testIncompatibleUnitConversion() {
        try {
            plugin.handleUnitConversion("1 km to kg")
            fail("Should throw for incompatible units")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Cannot convert"))
        }
    }

    // --- Execute method integration ---

    @Test
    fun testExecuteExpression() {
        val result = plugin.execute("2 + 3 * 4")
        assertTrue(result.isSuccess)
        assertEquals("14", result.data)
    }

    @Test
    fun testExecuteUnitConversion() {
        val result = plugin.execute("100 c to f")
        assertTrue(result.isSuccess)
        assertEquals("212", result.data)
    }

    @Test
    fun testExecuteError() {
        val result = plugin.execute("1 / 0")
        assertFalse(result.isSuccess)
    }

    // --- Plugin interface ---

    @Test
    fun testPluginId() {
        assertEquals("calculator", plugin.id)
    }

    @Test
    fun testPluginName() {
        assertEquals("Calculator", plugin.name)
    }

    @Test
    fun testPluginEnabled() {
        assertTrue(plugin.isEnabled)
        plugin.isEnabled = false
        assertFalse(plugin.isEnabled)
    }
}
