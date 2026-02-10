package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DateTimePluginTest {

    private lateinit var plugin: DateTimePlugin

    @Before
    fun setUp() {
        plugin = DateTimePlugin()
    }

    // --- Current time ---

    @Test
    fun testNowReturnsDateTime() {
        val result = plugin.execute("now")
        assertTrue(result.isSuccess)
        assertNotNull(result.data)
        // Should contain a year
        assertTrue(result.data!!.matches(".*\\d{4}-\\d{2}-\\d{2}.*".toRegex()))
    }

    @Test
    fun testNowWithTimezone() {
        val result = plugin.execute("now UTC")
        assertTrue(result.isSuccess)
        assertNotNull(result.data)
    }

    @Test
    fun testDefaultCommandIsNow() {
        val result = plugin.execute("anything")
        assertTrue(result.isSuccess)
        // Falls through to getCurrentTime("now")
    }

    // --- Date calculation ---

    @Test
    fun testAddDays() {
        val result = plugin.execute("add 5 days")
        assertTrue(result.isSuccess)
        assertNotNull(result.data)
        // Should be a valid date format
        assertTrue(result.data!!.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}".toRegex()))
    }

    @Test
    fun testAddHours() {
        val result = plugin.execute("add 3 hours")
        assertTrue(result.isSuccess)
    }

    @Test
    fun testSubtractDays() {
        val result = plugin.execute("subtract 10 days")
        assertTrue(result.isSuccess)
    }

    @Test
    fun testAddMonths() {
        val result = plugin.execute("add 2 months")
        assertTrue(result.isSuccess)
    }

    @Test
    fun testAddYears() {
        val result = plugin.execute("add 1 years")
        assertTrue(result.isSuccess)
    }

    @Test
    fun testAddInvalidUnit() {
        val result = plugin.execute("add 5 foos")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Unknown unit"))
    }

    @Test
    fun testAddMissingArgs() {
        val result = plugin.execute("add 5")
        assertFalse(result.isSuccess)
    }

    // --- Date difference ---

    @Test
    fun testDiffPastDate() {
        val result = plugin.execute("diff 2020-01-01")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("ago"))
    }

    @Test
    fun testDiffFutureDate() {
        val result = plugin.execute("diff 2099-01-01")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("from now"))
    }

    @Test
    fun testDiffMissingDate() {
        val result = plugin.execute("diff")
        assertFalse(result.isSuccess)
    }

    // --- Format ---

    @Test
    fun testFormatCustom() {
        val result = plugin.execute("format yyyy")
        assertTrue(result.isSuccess)
        // Should be current year
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(java.util.Date())
        assertEquals(year, result.data)
    }

    @Test
    fun testFormatMissingPattern() {
        val result = plugin.execute("format")
        assertFalse(result.isSuccess)
    }

    // --- Plugin interface ---

    @Test
    fun testPluginId() {
        assertEquals("datetime", plugin.id)
    }

    @Test
    fun testPluginName() {
        assertEquals("Date & Time", plugin.name)
    }

    @Test
    fun testPluginEnabled() {
        assertTrue(plugin.isEnabled)
    }
}
