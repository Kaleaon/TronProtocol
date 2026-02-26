package com.tronprotocol.app.selfmod

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ValidationResultTest {

    private lateinit var result: ValidationResult

    @Before
    fun setUp() {
        result = ValidationResult()
    }

    // ---- all stages exist in Stage enum ----

    @Test
    fun stage_proposed_exists() {
        assertNotNull(ValidationResult.Stage.PROPOSED)
    }

    @Test
    fun stage_syntaxStaticCheck_exists() {
        assertNotNull(ValidationResult.Stage.SYNTAX_STATIC_CHECK)
    }

    @Test
    fun stage_policyCheck_exists() {
        assertNotNull(ValidationResult.Stage.POLICY_CHECK)
    }

    @Test
    fun stage_sandboxTest_exists() {
        assertNotNull(ValidationResult.Stage.SANDBOX_TEST)
    }

    @Test
    fun stage_preflighted_exists() {
        assertNotNull(ValidationResult.Stage.PREFLIGHTED)
    }

    @Test
    fun stage_canary_exists() {
        assertNotNull(ValidationResult.Stage.CANARY)
    }

    @Test
    fun stage_promoted_exists() {
        assertNotNull(ValidationResult.Stage.PROMOTED)
    }

    @Test
    fun stage_rolledBack_exists() {
        assertNotNull(ValidationResult.Stage.ROLLED_BACK)
    }

    @Test
    fun stage_totalCount() {
        assertEquals(8, ValidationResult.Stage.values().size)
    }

    // ---- initially valid is false (no setValid(true) called) ----

    @Test
    fun initialState_isValidReturnsFalse() {
        // The initial value of valid is false and errors is empty.
        // isValid() returns valid && errors.isEmpty(), so false && true = false.
        assertFalse(result.isValid())
    }

    @Test
    fun initialState_stageIsProposed() {
        assertEquals(ValidationResult.Stage.PROPOSED, result.getStage())
    }

    @Test
    fun initialState_gateResultsEmpty() {
        assertTrue(result.getGateResults().isEmpty())
    }

    @Test
    fun initialState_errorsEmpty() {
        assertTrue(result.getErrors().isEmpty())
    }

    @Test
    fun initialState_warningsEmpty() {
        assertTrue(result.getWarnings().isEmpty())
    }

    // ---- setValid(true) with no errors makes isValid true ----

    @Test
    fun setValidTrue_noErrors_isValidReturnsTrue() {
        result.setValid(true)
        assertTrue(result.isValid())
    }

    // ---- addGateResult with passed=true keeps valid ----

    @Test
    fun addGateResult_passedTrue_keepsValidState() {
        result.setValid(true)
        result.addGateResult("syntax_check", true, "All syntax valid")
        assertTrue(result.isValid())
    }

    @Test
    fun addGateResult_passedTrue_recordsGate() {
        result.setValid(true)
        result.addGateResult("syntax_check", true, "Clean")
        assertEquals(1, result.getGateResults().size)
        assertTrue(result.getGateResults()[0].passed)
    }

    // ---- addGateResult with passed=false makes isValid() return false ----

    @Test
    fun addGateResult_passedFalse_makesInvalid() {
        result.setValid(true)
        result.addGateResult("safety_check", false, "Unsafe code detected")
        assertFalse(result.isValid())
    }

    @Test
    fun addGateResult_passedFalse_recordsGate() {
        result.addGateResult("safety_check", false, "Violation found")
        assertEquals(1, result.getGateResults().size)
        assertFalse(result.getGateResults()[0].passed)
        assertEquals("Violation found", result.getGateResults()[0].details)
    }

    // ---- addError makes isValid false ----

    @Test
    fun addError_makesInvalid() {
        result.setValid(true)
        result.addError("Syntax error on line 42")
        assertFalse(result.isValid())
    }

    @Test
    fun addError_recordsError() {
        result.addError("Missing bracket")
        assertEquals(1, result.getErrors().size)
        assertEquals("Missing bracket", result.getErrors()[0])
    }

    // ---- addWarning does not affect validity ----

    @Test
    fun addWarning_doesNotAffectValidity() {
        result.setValid(true)
        result.addWarning("Deprecated API usage")
        assertTrue(result.isValid())
    }

    @Test
    fun addWarning_recordsWarning() {
        result.addWarning("Consider refactoring")
        assertEquals(1, result.getWarnings().size)
        assertEquals("Consider refactoring", result.getWarnings()[0])
    }

    // ---- GateResult captures details ----

    @Test
    fun gateResult_capturesAllFields() {
        result.addGateResult("sandbox_test", true, "All assertions passed")
        val gate = result.getGateResults()[0]

        assertEquals("sandbox_test", gate.gateName)
        assertTrue(gate.passed)
        assertEquals("All assertions passed", gate.details)
        assertTrue(gate.timestamp > 0)
    }

    @Test
    fun gateResult_timestampIsSet() {
        val before = System.currentTimeMillis()
        result.addGateResult("test_gate", true, "ok")
        val after = System.currentTimeMillis()

        val gate = result.getGateResults()[0]
        assertTrue(gate.timestamp >= before)
        assertTrue(gate.timestamp <= after)
    }

    // ---- multiple gates can be recorded ----

    @Test
    fun multipleGates_allRecorded() {
        result.setValid(true)
        result.addGateResult("syntax", true, "OK")
        result.addGateResult("safety", true, "Safe")
        result.addGateResult("semantic", true, "Makes sense")
        result.addGateResult("sandbox", false, "Test failed")
        result.addGateResult("canary", true, "Healthy")

        assertEquals(5, result.getGateResults().size)
    }

    @Test
    fun multipleGates_failedGateMakesResultInvalid() {
        result.setValid(true)
        result.addGateResult("syntax", true, "OK")
        result.addGateResult("safety", true, "Safe")
        result.addGateResult("sandbox", false, "Failed assertion")

        assertFalse(result.isValid())
    }

    @Test
    fun multipleGates_allPassedKeepsValid() {
        result.setValid(true)
        result.addGateResult("syntax", true, "OK")
        result.addGateResult("safety", true, "Safe")
        result.addGateResult("sandbox", true, "Passed")

        assertTrue(result.isValid())
    }

    // ---- setStage updates stage ----

    @Test
    fun setStage_updatesStage() {
        result.setStage(ValidationResult.Stage.CANARY)
        assertEquals(ValidationResult.Stage.CANARY, result.getStage())
    }

    @Test
    fun setStage_canTransitionThroughStages() {
        result.setStage(ValidationResult.Stage.SYNTAX_STATIC_CHECK)
        assertEquals(ValidationResult.Stage.SYNTAX_STATIC_CHECK, result.getStage())

        result.setStage(ValidationResult.Stage.POLICY_CHECK)
        assertEquals(ValidationResult.Stage.POLICY_CHECK, result.getStage())

        result.setStage(ValidationResult.Stage.SANDBOX_TEST)
        assertEquals(ValidationResult.Stage.SANDBOX_TEST, result.getStage())

        result.setStage(ValidationResult.Stage.PREFLIGHTED)
        assertEquals(ValidationResult.Stage.PREFLIGHTED, result.getStage())
    }

    // ---- getGateResults returns copy ----

    @Test
    fun getGateResults_returnsCopy() {
        result.addGateResult("test", true, "ok")
        val list1 = result.getGateResults()
        val list2 = result.getGateResults()
        assertNotSame(list1, list2)
        assertEquals(list1, list2)
    }

    // ---- toString ----

    @Test
    fun toString_containsRelevantInfo() {
        result.setValid(true)
        result.addGateResult("syntax", true, "OK")
        result.addWarning("minor issue")

        val str = result.toString()
        assertTrue(str.contains("valid=true"))
        assertTrue(str.contains("gates=1"))
        assertTrue(str.contains("warnings=1"))
    }
}
