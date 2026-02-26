package com.tronprotocol.app.affect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AffectEngineTest {

    private lateinit var engine: AffectEngine

    @Before
    fun setUp() {
        engine = AffectEngine(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        if (engine.isRunning()) {
            engine.stop()
        }
    }

    @Test
    fun initialState_isNotRunning() {
        assertFalse("Engine should not be running initially", engine.isRunning())
    }

    @Test
    fun getCurrentState_returnsNonNullAffectState() {
        val state = engine.getCurrentState()
        assertNotNull("getCurrentState should return a non-null AffectState", state)
    }

    @Test
    fun submitInput_acceptsAffectInputWithoutError() {
        val input = AffectInput.builder("test:source")
            .valence(0.3f)
            .arousal(0.2f)
            .build()

        // Should not throw
        engine.submitInput(input)
    }

    @Test
    fun startAndStop_lifecycleWorks() {
        assertFalse(engine.isRunning())

        engine.start()
        assertTrue("Engine should be running after start()", engine.isRunning())

        engine.stop()
        assertFalse("Engine should not be running after stop()", engine.isRunning())
    }

    @Test
    fun startCalledMultipleTimes_isIdempotent() {
        engine.start()
        assertTrue(engine.isRunning())

        // Calling start again should not throw or change state
        engine.start()
        assertTrue(engine.isRunning())

        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun stopCalledWithoutStart_doesNotThrow() {
        assertFalse(engine.isRunning())
        // Calling stop without start should not throw
        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun initialAffectDimensions_areAtBaseline() {
        val state = engine.getCurrentState()
        for (dim in AffectDimension.entries) {
            assertEquals(
                "Dimension ${dim.key} should be at baseline ${dim.baseline}",
                dim.baseline, state[dim], 0.001f
            )
        }
    }

    @Test
    fun multipleInputs_canBeSubmitted() {
        val input1 = AffectInput.builder("test:source1")
            .valence(0.1f)
            .build()
        val input2 = AffectInput.builder("test:source2")
            .arousal(0.2f)
            .build()
        val input3 = AffectInput.builder("test:source3")
            .frustration(0.3f)
            .build()

        // All three should be accepted without error
        engine.submitInput(input1)
        engine.submitInput(input2)
        engine.submitInput(input3)
    }

    @Test
    fun addAndRemoveListener_worksWithoutError() {
        val listener = object : AffectEngine.AffectStateListener {
            override fun onAffectStateUpdated(state: AffectState, tickCount: Long) {
                // no-op
            }
        }

        engine.addListener(listener)
        engine.removeListener(listener)
    }

    @Test
    fun recordPartnerInput_doesNotThrow() {
        // recordPartnerInput resets the longing timer and should not throw
        engine.recordPartnerInput()
    }

    @Test
    fun getIntensity_returnsNonNegativeValue() {
        val intensity = engine.getIntensity()
        assertTrue("Intensity should be non-negative", intensity >= 0.0f)
    }

    @Test
    fun getTickCount_initiallyZero() {
        assertEquals("Tick count should be 0 before starting", 0L, engine.getTickCount())
    }

    @Test
    fun getRecentSources_initiallyEmpty() {
        val sources = engine.getRecentSources()
        assertTrue("Recent sources should be empty initially", sources.isEmpty())
    }

    @Test
    fun getStats_returnsNonEmptyMap() {
        val stats = engine.getStats()
        assertNotNull(stats)
        assertTrue("Stats should contain 'running' key", stats.containsKey("running"))
        assertTrue("Stats should contain 'tick_count' key", stats.containsKey("tick_count"))
        assertTrue("Stats should contain 'intensity' key", stats.containsKey("intensity"))
    }
}
