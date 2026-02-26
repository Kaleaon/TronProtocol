package com.tronprotocol.app.nct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tronprotocol.app.phylactery.ContinuumMemorySystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NarrativeContinuityTestTest {

    private lateinit var context: Context
    private lateinit var nct: NarrativeContinuityTest
    private lateinit var memorySystem: ContinuumMemorySystem

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nct = NarrativeContinuityTest(context)
        memorySystem = ContinuumMemorySystem(context)
    }

    @Test
    fun runTest_producesResult() {
        // Add some memory content so the test has something to work with
        memorySystem.addEpisodicMemory("Had a productive conversation about programming")
        memorySystem.addSemanticKnowledge("User prefers Kotlin over Java", "preferences")

        val run = nct.runFullTest(memorySystem)
        assertNotNull("NCT test run should produce a result", run)
        assertTrue(
            "Overall score should be between 0 and 1",
            run.overallScore in 0.0f..1.0f
        )
        assertNotNull("Results map should not be null", run.results)
    }

    @Test
    fun getAxes_returnsFiveAxes() {
        val axes = NCTAxis.entries
        assertEquals("NCT should have exactly 5 axes", 5, axes.size)

        // Verify all expected axes are present
        assertNotNull(NCTAxis.SITUATED_MEMORY)
        assertNotNull(NCTAxis.GOAL_PERSISTENCE)
        assertNotNull(NCTAxis.AUTONOMOUS_SELF_CORRECTION)
        assertNotNull(NCTAxis.STYLISTIC_SEMANTIC_STABILITY)
        assertNotNull(NCTAxis.PERSONA_ROLE_CONTINUITY)
    }

    @Test
    fun result_hasScoresForAllAxes() {
        val run = nct.runFullTest(memorySystem)

        for (axis in NCTAxis.entries) {
            val result = run.results[axis]
            assertNotNull("Result should have a score for axis ${axis.label}", result)
            assertTrue(
                "Score for ${axis.label} should be between 0 and 1, got ${result!!.score}",
                result.score in 0.0f..1.0f
            )
            assertNotNull("Result for ${axis.label} should have details", result.details)
            assertTrue("Details for ${axis.label} should not be empty", result.details.isNotEmpty())
        }
    }
}
