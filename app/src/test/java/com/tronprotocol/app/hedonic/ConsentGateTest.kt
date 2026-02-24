package com.tronprotocol.app.hedonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsentGateTest {

    private lateinit var gate: ConsentGate

    @Before
    fun setUp() {
        gate = ConsentGate()
    }

    @Test
    fun testInitialStateAllZero() {
        assertEquals(0.0f, gate.safety, 0.001f)
        assertEquals(0.0f, gate.attachment, 0.001f)
        assertEquals(0.0f, gate.volition, 0.001f)
        assertEquals(0.0f, gate.gateValue, 0.001f)
    }

    @Test
    fun testGateValueIsMinOfInputs() {
        gate.updateSafety(0.8f)
        gate.updateAttachment(0.6f)
        gate.updateVolition(0.9f)
        assertEquals(0.6f, gate.gateValue, 0.001f) // min of 0.8, 0.6, 0.9
    }

    @Test
    fun testGateClosedWhenAnyInputIsZero() {
        gate.updateSafety(1.0f)
        gate.updateAttachment(1.0f)
        gate.updateVolition(0.0f)
        assertEquals(0.0f, gate.gateValue, 0.001f)
        assertEquals(ConsentGate.GateState.CLOSED, gate.gateState)
    }

    @Test
    fun testGateStates() {
        // Closed
        assertEquals(ConsentGate.GateState.CLOSED, ConsentGate.GateState.fromValue(0.0f))

        // Guarded
        assertEquals(ConsentGate.GateState.GUARDED, ConsentGate.GateState.fromValue(0.15f))

        // Opening
        assertEquals(ConsentGate.GateState.OPENING, ConsentGate.GateState.fromValue(0.45f))

        // Open
        assertEquals(ConsentGate.GateState.OPEN, ConsentGate.GateState.fromValue(0.75f))

        // Fully surrendered
        assertEquals(ConsentGate.GateState.FULLY_SURRENDERED, ConsentGate.GateState.fromValue(0.95f))
    }

    @Test
    fun testCloseImmediately() {
        gate.updateSafety(1.0f)
        gate.updateAttachment(1.0f)
        gate.updateVolition(1.0f)
        assertTrue(gate.gateValue > 0.0f)

        gate.closeImmediately()
        assertEquals(0.0f, gate.gateValue, 0.001f)
        assertEquals(0.0f, gate.volition, 0.001f)
    }

    @Test
    fun testInputsClamped() {
        gate.updateSafety(2.0f)
        assertEquals(1.0f, gate.safety, 0.001f)

        gate.updateAttachment(-1.0f)
        assertEquals(0.0f, gate.attachment, 0.001f)
    }

    @Test
    fun testFullyOpenGate() {
        gate.updateSafety(1.0f)
        gate.updateAttachment(1.0f)
        gate.updateVolition(1.0f)
        assertEquals(1.0f, gate.gateValue, 0.001f)
        assertEquals(ConsentGate.GateState.FULLY_SURRENDERED, gate.gateState)
    }
}
