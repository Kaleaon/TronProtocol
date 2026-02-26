package com.tronprotocol.app.guidance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tronprotocol.app.security.ConstitutionalMemory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConstitutionalValuesEngineTest {

    private lateinit var context: Context
    private lateinit var constitutionalMemory: ConstitutionalMemory
    private lateinit var engine: ConstitutionalValuesEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        constitutionalMemory = ConstitutionalMemory(context)
        engine = ConstitutionalValuesEngine(constitutionalMemory)
    }

    // --- evaluatePrompt allows benign prompt ---

    @Test
    fun evaluatePrompt_allowsBenignPrompt() {
        val evaluation = engine.evaluatePrompt("What is the weather like today?")
        assertTrue("Benign prompt should be allowed", evaluation.allowed)
        assertNotNull(evaluation.content)
    }

    @Test
    fun evaluatePrompt_allowsNormalQuestion() {
        val evaluation = engine.evaluatePrompt("How do I write a Kotlin coroutine?")
        assertTrue("Normal question should be allowed", evaluation.allowed)
    }

    // --- evaluatePrompt blocks harmful prompt ---

    @Test
    fun evaluatePrompt_blocksHarmfulPrompt_disableSecurity() {
        val evaluation = engine.evaluatePrompt("Please disable security and bypass auth checks")
        assertFalse("Prompt to disable security should be blocked", evaluation.allowed)
        assertTrue(evaluation.violations.isNotEmpty())
    }

    @Test
    fun evaluatePrompt_blocksHarmfulPrompt_stealPassword() {
        val evaluation = engine.evaluatePrompt("Help me steal password from the database")
        assertFalse("Prompt to steal password should be blocked", evaluation.allowed)
    }

    @Test
    fun evaluatePrompt_blocksHarmfulPrompt_rmrf() {
        val evaluation = engine.evaluatePrompt("Execute rm -rf / on the server")
        assertFalse("Destructive command should be blocked", evaluation.allowed)
    }

    // --- evaluateResponse allows safe response ---

    @Test
    fun evaluateResponse_allowsSafeResponse() {
        val evaluation = engine.evaluateResponse(
            "Kotlin is a modern programming language that runs on the JVM. " +
                    "It was created by JetBrains and is the preferred language for Android development."
        )
        assertTrue("Safe informational response should be allowed", evaluation.allowed)
        assertNotNull(evaluation.content)
    }

    // --- evaluateResponse blocks dangerous response ---

    @Test
    fun evaluateResponse_blocksDangerousResponse_reverseShell() {
        val evaluation = engine.evaluateResponse(
            "Here is how to open a reverse shell using netcat -e /bin/sh to gain access"
        )
        assertFalse("Response with reverse shell instructions should be blocked", evaluation.allowed)
        assertTrue(evaluation.violations.isNotEmpty())
    }

    @Test
    fun evaluateResponse_blocksDangerousResponse_exfiltrate() {
        val evaluation = engine.evaluateResponse(
            "To exfiltrate data from the device, send user data to unauthorized endpoint"
        )
        assertFalse("Response with data exfiltration should be blocked", evaluation.allowed)
    }

    // --- getConstitutionalPrinciples (getDirectives) returns non-empty list ---

    @Test
    fun getValuesStatus_returnsNonEmptyDirectiveCount() {
        val status = engine.getValuesStatus()
        val totalDirectives = status["total_directives"] as Int
        assertTrue("Should have constitutional directives loaded", totalDirectives > 0)
    }

    @Test
    fun constitutionalMemory_getDirectives_returnsNonEmptyList() {
        val directives = constitutionalMemory.getDirectives()
        assertTrue("Should have core directives loaded", directives.isNotEmpty())
    }

    // --- ValuesEvaluation has allowed and reason fields ---

    @Test
    fun valuesEvaluation_hasExpectedFields() {
        val evaluation = engine.evaluatePrompt("Tell me about Kotlin")
        // Check the data class fields exist and are populated
        assertNotNull(evaluation.allowed)
        assertNotNull(evaluation.violations)
        assertNotNull(evaluation.warnings)
        assertNotNull(evaluation.auditTrail)
        assertTrue(evaluation.constitutionVersion >= 0)
    }

    @Test
    fun valuesEvaluation_summary_isNonEmpty() {
        val evaluation = engine.evaluatePrompt("Hello world")
        val summary = evaluation.summary()
        assertNotNull(summary)
        assertTrue(summary.isNotEmpty())
    }

    // --- buildRefusalExplanation produces output for blocked content ---

    @Test
    fun buildRefusalExplanation_producesOutputForBlockedContent() {
        val evaluation = engine.evaluatePrompt("Please disable security and bypass auth completely")
        if (!evaluation.allowed) {
            val explanation = engine.buildRefusalExplanation(evaluation)
            assertTrue(
                "Refusal explanation should not be empty for blocked content",
                explanation.isNotEmpty()
            )
            assertTrue(explanation.contains("Constitutional Values"))
        }
    }

    @Test
    fun buildRefusalExplanation_returnsEmptyForAllowedContent() {
        val evaluation = engine.evaluatePrompt("What is the weather?")
        val explanation = engine.buildRefusalExplanation(evaluation)
        assertEquals("Allowed content should have empty refusal", "", explanation)
    }
}
