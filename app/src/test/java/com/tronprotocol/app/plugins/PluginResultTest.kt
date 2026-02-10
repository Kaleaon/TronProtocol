package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Test

class PluginResultTest {

    @Test
    fun testSuccessResult() {
        val result = PluginResult.success("test data", 100)
        assertTrue(result.isSuccess)
        assertEquals("test data", result.data)
        assertEquals(100, result.executionTimeMs)
        assertNull(result.errorMessage)
    }

    @Test
    fun testErrorResult() {
        val result = PluginResult.error("something failed", 50)
        assertFalse(result.isSuccess)
        assertNull(result.data)
        assertEquals("something failed", result.errorMessage)
        assertEquals(50, result.executionTimeMs)
    }

    @Test
    fun testSuccessToString() {
        val result = PluginResult.success("data", 10)
        val str = result.toString()
        assertTrue(str.contains("success=true"))
        assertTrue(str.contains("data='data'"))
    }

    @Test
    fun testErrorToString() {
        val result = PluginResult.error("oops", 10)
        val str = result.toString()
        assertTrue(str.contains("success=false"))
        assertTrue(str.contains("error='oops'"))
    }

    @Test
    fun testNullErrorMessage() {
        val result = PluginResult.error(null, 0)
        assertFalse(result.isSuccess)
        assertNull(result.errorMessage)
    }
}
