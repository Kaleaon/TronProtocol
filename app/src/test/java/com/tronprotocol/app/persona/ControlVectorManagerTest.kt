package com.tronprotocol.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ControlVectorManagerTest {

    private lateinit var manager: ControlVectorManager

    @Before
    fun setUp() {
        manager = ControlVectorManager()
    }

    @Test
    fun initialAxisValuesAreZero() {
        ControlVectorManager.ALL_AXES.forEach { axis ->
            assertEquals(0f, manager.getAxis(axis), 0.001f)
        }
    }

    @Test
    fun setAxisClampsToRange() {
        manager.setAxis(ControlVectorManager.AXIS_WARMTH, 2.0f)
        assertEquals(1.0f, manager.getAxis(ControlVectorManager.AXIS_WARMTH), 0.001f)

        manager.setAxis(ControlVectorManager.AXIS_WARMTH, -2.0f)
        assertEquals(-1.0f, manager.getAxis(ControlVectorManager.AXIS_WARMTH), 0.001f)
    }

    @Test
    fun setAxisPreservesValue() {
        manager.setAxis(ControlVectorManager.AXIS_HUMOR, 0.7f)
        assertEquals(0.7f, manager.getAxis(ControlVectorManager.AXIS_HUMOR), 0.001f)
    }

    @Test
    fun getAllAxesReturnsAllSixAxes() {
        val axes = manager.getAllAxes()
        assertEquals(6, axes.size)
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_WARMTH))
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_ENERGY))
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_HUMOR))
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_FORMALITY))
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_VERBOSITY))
        assertTrue(axes.containsKey(ControlVectorManager.AXIS_EMOTION))
    }

    @Test
    fun applyPersonaSetsAxesFromControlVectors() {
        val persona = Persona(
            id = "test",
            name = "Test",
            controlVectorsJson = """{"warmth": 0.8, "energy": -0.3, "humor": 0.5}"""
        )

        manager.applyPersonality(persona)

        assertEquals(0.8f, manager.getAxis(ControlVectorManager.AXIS_WARMTH), 0.001f)
        assertEquals(-0.3f, manager.getAxis(ControlVectorManager.AXIS_ENERGY), 0.001f)
        assertEquals(0.5f, manager.getAxis(ControlVectorManager.AXIS_HUMOR), 0.001f)
    }

    @Test
    fun saveAndRestoreInterventionState() {
        manager.setAxis(ControlVectorManager.AXIS_WARMTH, 0.7f)
        manager.setAxis(ControlVectorManager.AXIS_HUMOR, -0.4f)
        manager.updateEmotionState(EmotionRegime.PLAYFUL)

        val state = manager.saveInterventionState()
        assertTrue(state.contains("warmth"))
        assertTrue(state.contains("0.7"))

        // Create new manager and restore
        val newManager = ControlVectorManager()
        newManager.restoreInterventionState(state)

        assertEquals(0.7f, newManager.getAxis(ControlVectorManager.AXIS_WARMTH), 0.01f)
        assertEquals(-0.4f, newManager.getAxis(ControlVectorManager.AXIS_HUMOR), 0.01f)
    }

    @Test
    fun clearAllInterventionsResetsToZero() {
        manager.setAxis(ControlVectorManager.AXIS_WARMTH, 0.9f)
        manager.setAxis(ControlVectorManager.AXIS_ENERGY, 0.5f)

        manager.clearAllInterventions()

        assertEquals(0f, manager.getAxis(ControlVectorManager.AXIS_WARMTH), 0.001f)
        assertEquals(0f, manager.getAxis(ControlVectorManager.AXIS_ENERGY), 0.001f)
    }

    @Test
    fun allAxesConstantHasSixEntries() {
        assertEquals(6, ControlVectorManager.ALL_AXES.size)
    }
}
