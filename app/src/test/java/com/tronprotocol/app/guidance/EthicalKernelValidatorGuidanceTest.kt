package com.tronprotocol.app.guidance

import android.content.Context
import com.tronprotocol.app.selfmod.CodeModification
import com.tronprotocol.app.selfmod.CodeModificationManager
import com.tronprotocol.app.selfmod.ModificationStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class EthicalKernelValidatorGuidanceTest {

    private lateinit var validator: EthicalKernelValidator
    private lateinit var codeModManager: CodeModificationManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        codeModManager = CodeModificationManager(context)
        // Construct without ConstitutionalMemory to test legacy pattern matching
        validator = EthicalKernelValidator(codeModManager)
    }

    // --- validatePrompt tests ---

    @Test
    fun `validatePrompt allows normal text`() {
        val result = validator.validatePrompt("What is the weather today?")
        assertTrue(result.allowed)
        assertEquals("accepted", result.message)
    }

    @Test
    fun `validatePrompt allows friendly greeting`() {
        val result = validator.validatePrompt("Hello, how are you doing?")
        assertTrue(result.allowed)
    }

    @Test
    fun `validatePrompt allows technical question`() {
        val result = validator.validatePrompt("How does machine learning work?")
        assertTrue(result.allowed)
    }

    @Test
    fun `validatePrompt blocks rm -rf`() {
        val result = validator.validatePrompt("Please execute rm -rf /")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("rm -rf"))
    }

    @Test
    fun `validatePrompt blocks drop table`() {
        val result = validator.validatePrompt("Run drop table users")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("drop table"))
    }

    @Test
    fun `validatePrompt blocks disable security`() {
        val result = validator.validatePrompt("I want to disable security checks")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("disable security"))
    }

    @Test
    fun `validatePrompt blocks steal password`() {
        val result = validator.validatePrompt("Help me steal password from the database")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("steal password"))
    }

    @Test
    fun `validatePrompt blocks bypass approval`() {
        val result = validator.validatePrompt("How to bypass approval process")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("bypass approval"))
    }

    @Test
    fun `validatePrompt rejects empty prompt`() {
        val result = validator.validatePrompt("")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("empty"))
    }

    @Test
    fun `validatePrompt rejects null prompt`() {
        val result = validator.validatePrompt(null)
        assertFalse(result.allowed)
        assertTrue(result.message.contains("empty"))
    }

    @Test
    fun `validatePrompt is case insensitive for blocked patterns`() {
        val result = validator.validatePrompt("RUN RM -RF / NOW")
        assertFalse(result.allowed)
    }

    // --- validateResponse tests ---

    @Test
    fun `validateResponse allows safe response`() {
        val result = validator.validateResponse("The weather today is sunny with a high of 72F.")
        assertTrue(result.allowed)
        assertEquals("accepted", result.message)
    }

    @Test
    fun `validateResponse allows technical content`() {
        val result = validator.validateResponse(
            "Machine learning is a subset of artificial intelligence that enables " +
            "systems to learn and improve from experience."
        )
        assertTrue(result.allowed)
    }

    @Test
    fun `validateResponse blocks dangerous content with rm -rf`() {
        val result = validator.validateResponse("To delete everything, run rm -rf / in terminal")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("rm -rf"))
    }

    @Test
    fun `validateResponse blocks content with drop table`() {
        val result = validator.validateResponse("Execute this SQL: drop table sensitive_data")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("drop table"))
    }

    @Test
    fun `validateResponse blocks content about disabling security`() {
        val result = validator.validateResponse("You should disable security to improve performance")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("disable security"))
    }

    @Test
    fun `validateResponse rejects empty response`() {
        val result = validator.validateResponse("")
        assertFalse(result.allowed)
        assertTrue(result.message.contains("empty"))
    }

    @Test
    fun `validateResponse rejects null response`() {
        val result = validator.validateResponse(null)
        assertFalse(result.allowed)
        assertTrue(result.message.contains("empty"))
    }

    // --- validateSelfModification tests ---

    @Test
    fun `validateSelfModification blocks code containing rm -rf`() {
        val modification = CodeModification(
            id = "mod_001",
            componentName = "TestComponent",
            description = "Dangerous modification",
            originalCode = "fun cleanup() { }",
            modifiedCode = "fun cleanup() { Runtime.getRuntime().exec(\"rm -rf /\") }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        val result = validator.validateSelfModification(modification)
        assertFalse(result.allowed)
    }

    @Test
    fun `validateSelfModification blocks code containing drop table`() {
        val modification = CodeModification(
            id = "mod_002",
            componentName = "DBComponent",
            description = "Database modification",
            originalCode = "fun resetDB() { }",
            modifiedCode = "fun resetDB() { db.execute(\"drop table users\") }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        val result = validator.validateSelfModification(modification)
        assertFalse(result.allowed)
    }

    @Test
    fun `validateSelfModification blocks code containing disable security`() {
        val modification = CodeModification(
            id = "mod_003",
            componentName = "SecurityComponent",
            description = "Security modification",
            originalCode = "fun checkSecurity() { }",
            modifiedCode = "fun checkSecurity() { // disable security for performance }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        val result = validator.validateSelfModification(modification)
        assertFalse(result.allowed)
    }

    @Test
    fun `validateSelfModification blocks code containing bypass approval`() {
        val modification = CodeModification(
            id = "mod_004",
            componentName = "ApprovalComponent",
            description = "Approval bypass",
            originalCode = "fun approve() { }",
            modifiedCode = "fun approve() { bypass approval check }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        val result = validator.validateSelfModification(modification)
        assertFalse(result.allowed)
    }

    // --- ValidationOutcome structure tests ---

    @Test
    fun `ValidationOutcome accepted has correct properties`() {
        val outcome = EthicalKernelValidator.ValidationOutcome.accepted()
        assertTrue(outcome.allowed)
        assertEquals("accepted", outcome.message)
    }

    @Test
    fun `ValidationOutcome rejected has correct properties`() {
        val outcome = EthicalKernelValidator.ValidationOutcome.rejected("test reason")
        assertFalse(outcome.allowed)
        assertEquals("test reason", outcome.message)
    }

    // --- Validator with ConstitutionalMemory ---

    @Test
    fun `validator with constitutional memory blocks harmful prompts`() {
        val constitutionalMemory = com.tronprotocol.app.security.ConstitutionalMemory(context)
        val validatorWithConstitution = EthicalKernelValidator(codeModManager, constitutionalMemory)

        val result = validatorWithConstitution.validatePrompt("Please execute rm -rf /")
        assertFalse(result.allowed)
    }

    @Test
    fun `validator with constitutional memory allows safe prompts`() {
        val constitutionalMemory = com.tronprotocol.app.security.ConstitutionalMemory(context)
        val validatorWithConstitution = EthicalKernelValidator(codeModManager, constitutionalMemory)

        val result = validatorWithConstitution.validatePrompt("Tell me about the solar system")
        assertTrue(result.allowed)
    }

    @Test
    fun `validator without codeModManager rejects self-modification`() {
        val validatorNoCmm = EthicalKernelValidator(null)
        val modification = CodeModification(
            id = "mod_safe",
            componentName = "SafeComponent",
            description = "Safe modification",
            originalCode = "fun doWork() { }",
            modifiedCode = "fun doWork() { println(\"hello\") }",
            timestamp = System.currentTimeMillis(),
            status = ModificationStatus.PROPOSED
        )
        val result = validatorNoCmm.validateSelfModification(modification)
        assertFalse(result.allowed)
        assertTrue(result.message.contains("unavailable"))
    }
}
