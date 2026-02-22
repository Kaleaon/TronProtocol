package com.tronprotocol.app.nct

import org.junit.Assert.assertEquals
import org.junit.Test

class NCTAxisTest {

    @Test
    fun testFiveAxesExist() {
        assertEquals(5, NCTAxis.entries.size)
    }

    @Test
    fun testAxisLabels() {
        assertEquals("situated_memory", NCTAxis.SITUATED_MEMORY.label)
        assertEquals("goal_persistence", NCTAxis.GOAL_PERSISTENCE.label)
        assertEquals("autonomous_self_correction", NCTAxis.AUTONOMOUS_SELF_CORRECTION.label)
        assertEquals("stylistic_semantic_stability", NCTAxis.STYLISTIC_SEMANTIC_STABILITY.label)
        assertEquals("persona_role_continuity", NCTAxis.PERSONA_ROLE_CONTINUITY.label)
    }

    @Test
    fun testNCTResultCreation() {
        val result = NCTResult(
            axis = NCTAxis.SITUATED_MEMORY,
            score = 0.85f,
            passed = true,
            details = "5 memories tested, all accurate"
        )
        assertEquals(NCTAxis.SITUATED_MEMORY, result.axis)
        assertEquals(0.85f, result.score, 0.001f)
        assertEquals(true, result.passed)
    }

    @Test
    fun testNCTResultJsonRoundTrip() {
        val result = NCTResult(
            axis = NCTAxis.GOAL_PERSISTENCE,
            score = 0.7f,
            passed = true,
            details = "3 of 4 goals persisted"
        )
        val json = result.toJson()
        val restored = NCTResult.fromJson(json)
        assertEquals(result.axis, restored.axis)
        assertEquals(result.score, restored.score, 0.001f)
        assertEquals(result.passed, restored.passed)
        assertEquals(result.details, restored.details)
    }

    @Test
    fun testNCTTestRunAllPassed() {
        val results = mapOf(
            NCTAxis.SITUATED_MEMORY to NCTResult(NCTAxis.SITUATED_MEMORY, 0.9f, true, "ok"),
            NCTAxis.GOAL_PERSISTENCE to NCTResult(NCTAxis.GOAL_PERSISTENCE, 0.8f, true, "ok"),
            NCTAxis.AUTONOMOUS_SELF_CORRECTION to NCTResult(NCTAxis.AUTONOMOUS_SELF_CORRECTION, 0.7f, true, "ok"),
            NCTAxis.STYLISTIC_SEMANTIC_STABILITY to NCTResult(NCTAxis.STYLISTIC_SEMANTIC_STABILITY, 0.85f, true, "ok"),
            NCTAxis.PERSONA_ROLE_CONTINUITY to NCTResult(NCTAxis.PERSONA_ROLE_CONTINUITY, 0.95f, true, "ok")
        )
        val run = NCTTestRun(results, 0.84f)
        assertEquals(true, run.allPassed)
    }

    @Test
    fun testNCTTestRunNotAllPassed() {
        val results = mapOf(
            NCTAxis.SITUATED_MEMORY to NCTResult(NCTAxis.SITUATED_MEMORY, 0.9f, true, "ok"),
            NCTAxis.GOAL_PERSISTENCE to NCTResult(NCTAxis.GOAL_PERSISTENCE, 0.3f, false, "fail")
        )
        val run = NCTTestRun(results, 0.6f)
        assertEquals(false, run.allPassed)
    }
}
