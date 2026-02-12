package com.tronprotocol.app.frontier

import org.junit.Assert.*
import org.junit.Test

class AccessibilityResultTest {

    // --- FrontierState ---

    @Test
    fun testHighMuXIsAccessible() {
        val result = AccessibilityResult(muX = 0.9f, muY = 0.1f)
        assertEquals(FrontierState.ACCESSIBLE, result.frontierState)
    }

    @Test
    fun testLowMuXIsInaccessible() {
        val result = AccessibilityResult(muX = 0.1f, muY = 0.9f)
        assertEquals(FrontierState.INACCESSIBLE, result.frontierState)
    }

    @Test
    fun testMidMuXIsFrontier() {
        val result = AccessibilityResult(muX = 0.5f, muY = 0.5f)
        assertEquals(FrontierState.FRONTIER, result.frontierState)
    }

    @Test
    fun testBoundaryHighIsFrontier() {
        // 0.8 is on the boundary — should be FRONTIER (not >0.8)
        val result = AccessibilityResult(muX = 0.8f, muY = 0.2f)
        assertEquals(FrontierState.FRONTIER, result.frontierState)
    }

    @Test
    fun testBoundaryLowIsFrontier() {
        // 0.2 is on the boundary — should be FRONTIER (not <0.2)
        val result = AccessibilityResult(muX = 0.2f, muY = 0.8f)
        assertEquals(FrontierState.FRONTIER, result.frontierState)
    }

    @Test
    fun testJustAboveHighIsAccessible() {
        val result = AccessibilityResult(muX = 0.81f, muY = 0.19f)
        assertEquals(FrontierState.ACCESSIBLE, result.frontierState)
    }

    @Test
    fun testJustBelowLowIsInaccessible() {
        val result = AccessibilityResult(muX = 0.19f, muY = 0.81f)
        assertEquals(FrontierState.INACCESSIBLE, result.frontierState)
    }

    // --- Complementarity Error ---

    @Test
    fun testComplementarityErrorPerfect() {
        val result = AccessibilityResult(muX = 0.7f, muY = 0.3f)
        assertEquals(0.0f, result.complementarityError, 1e-6f)
    }

    @Test
    fun testComplementarityErrorDetected() {
        // Intentionally wrong: mu_x + mu_y != 1
        val result = AccessibilityResult(muX = 0.7f, muY = 0.4f)
        assertTrue(result.complementarityError > 0.09f)
    }

    // --- FrontierState.fromMuX ---

    @Test
    fun testFromMuXZero() {
        assertEquals(FrontierState.INACCESSIBLE, FrontierState.fromMuX(0.0f))
    }

    @Test
    fun testFromMuXOne() {
        assertEquals(FrontierState.ACCESSIBLE, FrontierState.fromMuX(1.0f))
    }

    @Test
    fun testFromMuXHalf() {
        assertEquals(FrontierState.FRONTIER, FrontierState.fromMuX(0.5f))
    }

    // --- Data class equality ---

    @Test
    fun testEqualResults() {
        val a = AccessibilityResult(muX = 0.5f, muY = 0.5f, prediction = 0)
        val b = AccessibilityResult(muX = 0.5f, muY = 0.5f, prediction = 0)
        assertEquals(a, b)
    }

    @Test
    fun testUnequalResults() {
        val a = AccessibilityResult(muX = 0.5f, muY = 0.5f, prediction = 0)
        val b = AccessibilityResult(muX = 0.6f, muY = 0.4f, prediction = 0)
        assertNotEquals(a, b)
    }
}
