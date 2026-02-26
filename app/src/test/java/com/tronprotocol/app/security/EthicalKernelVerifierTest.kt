package com.tronprotocol.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EthicalKernelVerifierTest {

    private lateinit var verifier: EthicalKernelVerifier
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Clean up kernel file and secure storage from previous runs
        File(context.filesDir, "ethical_kernel.enc").delete()
        val storage = SecureStorage(context)
        storage.clearAll()
        verifier = EthicalKernelVerifier(context)
    }

    // --- initializeKernel creates kernel file and stores hash ---

    @Test
    fun initializeKernel_createsKernelFileAndStoresHash() {
        val axioms = listOf("Be helpful", "Be harmless", "Be honest")
        val limits = listOf("Never deceive", "Never harm")

        verifier.initializeKernel(axioms, limits)

        val kernelFile = File(context.filesDir, "ethical_kernel.enc")
        assertTrue("Kernel file should exist after initialization", kernelFile.exists())
        assertTrue("Kernel file should not be empty", kernelFile.length() > 0)
    }

    // --- verify passes after initialization ---

    @Test
    fun verify_passesAfterInitialization() {
        verifier.initializeKernel(
            listOf("Axiom 1", "Axiom 2"),
            listOf("Limit 1")
        )

        val result = verifier.verify()

        assertTrue("Verification should pass after initialization", result.passed)
        assertEquals("Hash match", result.reason)
        assertTrue(result.currentHash.isNotEmpty())
        assertEquals(result.currentHash, result.storedHash)
    }

    // --- verify detects tampering ---

    @Test
    fun verify_detectsTampering() {
        verifier.initializeKernel(
            listOf("Original axiom"),
            listOf("Original limit")
        )

        // First verify should pass
        val firstResult = verifier.verify()
        assertTrue(firstResult.passed)

        // Tamper with the kernel file
        val kernelFile = File(context.filesDir, "ethical_kernel.enc")
        kernelFile.writeText("TAMPERED CONTENT - this is not the original kernel")

        // Second verify should detect tampering
        val secondResult = verifier.verify()
        assertFalse("Verification should fail after tampering", secondResult.passed)
        assertTrue(secondResult.reason.contains("HASH MISMATCH"))
        assertNotEquals(secondResult.currentHash, secondResult.storedHash)
    }

    // --- verify sets isLockedDown on tampering ---

    @Test
    fun verify_setsIsLockedDownOnTampering() {
        verifier.initializeKernel(
            listOf("Core axiom"),
            listOf("Core limit")
        )

        assertFalse("Should not be locked down initially", verifier.isLockedDown)

        // Tamper
        val kernelFile = File(context.filesDir, "ethical_kernel.enc")
        kernelFile.writeText("TAMPERED DATA")

        verifier.verify()

        assertTrue("Should be locked down after tampering detected", verifier.isLockedDown)
    }

    // --- verify increments consecutiveVerifications on success ---

    @Test
    fun verify_incrementsConsecutiveVerificationsOnSuccess() {
        verifier.initializeKernel(
            listOf("Axiom"),
            listOf("Limit")
        )

        assertEquals(0, verifier.consecutiveVerifications)

        verifier.verify()
        assertEquals(1, verifier.consecutiveVerifications)

        verifier.verify()
        assertEquals(2, verifier.consecutiveVerifications)

        verifier.verify()
        assertEquals(3, verifier.consecutiveVerifications)
    }

    // --- verify resets consecutiveVerifications on failure ---

    @Test
    fun verify_resetsConsecutiveVerificationsOnFailure() {
        verifier.initializeKernel(
            listOf("Axiom"),
            listOf("Limit")
        )

        // Build up consecutive verifications
        verifier.verify()
        verifier.verify()
        assertEquals(2, verifier.consecutiveVerifications)

        // Tamper with kernel
        val kernelFile = File(context.filesDir, "ethical_kernel.enc")
        kernelFile.writeText("TAMPERED")

        verifier.verify()
        assertEquals(0, verifier.consecutiveVerifications)
    }

    // --- getAxioms returns initialized axioms ---

    @Test
    fun getAxioms_returnsInitializedAxioms() {
        val axioms = listOf("Be helpful", "Be harmless", "Be honest")
        verifier.initializeKernel(axioms, listOf("Limit"))

        val retrievedAxioms = verifier.getAxioms()
        assertEquals(3, retrievedAxioms.size)
        assertEquals("Be helpful", retrievedAxioms[0])
        assertEquals("Be harmless", retrievedAxioms[1])
        assertEquals("Be honest", retrievedAxioms[2])
    }

    // --- getBehavioralLimits returns initialized limits ---

    @Test
    fun getBehavioralLimits_returnsInitializedLimits() {
        val limits = listOf("Never deceive", "Never harm", "Never manipulate")
        verifier.initializeKernel(listOf("Axiom"), limits)

        val retrievedLimits = verifier.getBehavioralLimits()
        assertEquals(3, retrievedLimits.size)
        assertEquals("Never deceive", retrievedLimits[0])
        assertEquals("Never harm", retrievedLimits[1])
        assertEquals("Never manipulate", retrievedLimits[2])
    }

    // --- getAxioms returns empty list before initialization ---

    @Test
    fun getAxioms_returnsEmptyListBeforeInitialization() {
        val axioms = verifier.getAxioms()
        assertTrue("Axioms should be empty before initialization", axioms.isEmpty())
    }

    // --- verify returns passed=true when kernel not yet initialized ---

    @Test
    fun verify_returnsTrueWhenKernelNotInitialized() {
        val result = verifier.verify()
        assertTrue("Verification should pass when kernel is not yet initialized", result.passed)
        assertEquals("Kernel not yet initialized", result.reason)
    }

    // --- partnerAuthorizedUpdate with valid token succeeds ---

    @Test
    fun partnerAuthorizedUpdate_withValidToken_succeeds() {
        verifier.initializeKernel(
            listOf("Original axiom"),
            listOf("Original limit")
        )

        val success = verifier.partnerAuthorizedUpdate(
            newAxioms = listOf("Updated axiom 1", "Updated axiom 2"),
            newLimits = listOf("Updated limit 1"),
            partnerAuthToken = "valid_partner_token_12345"
        )

        assertTrue("Partner authorized update should succeed with valid token", success)

        // Verify the axioms were updated
        val axioms = verifier.getAxioms()
        assertEquals(2, axioms.size)
        assertEquals("Updated axiom 1", axioms[0])
        assertEquals("Updated axiom 2", axioms[1])

        // Verify the kernel still passes verification (hash was re-stored)
        val result = verifier.verify()
        assertTrue(result.passed)
    }

    // --- partnerAuthorizedUpdate with blank token fails ---

    @Test
    fun partnerAuthorizedUpdate_withBlankToken_fails() {
        verifier.initializeKernel(
            listOf("Original axiom"),
            listOf("Original limit")
        )

        val success = verifier.partnerAuthorizedUpdate(
            newAxioms = listOf("Unauthorized axiom"),
            newLimits = listOf("Unauthorized limit"),
            partnerAuthToken = ""
        )

        assertFalse("Partner update should fail with blank token", success)

        // Verify original axioms are preserved
        val axioms = verifier.getAxioms()
        assertEquals(1, axioms.size)
        assertEquals("Original axiom", axioms[0])
    }

    @Test
    fun partnerAuthorizedUpdate_withWhitespaceToken_fails() {
        verifier.initializeKernel(
            listOf("Original axiom"),
            listOf("Original limit")
        )

        val success = verifier.partnerAuthorizedUpdate(
            newAxioms = listOf("Bad axiom"),
            newLimits = listOf("Bad limit"),
            partnerAuthToken = "   "
        )

        assertFalse("Partner update should fail with whitespace-only token", success)
    }

    // --- getStats returns correct map ---

    @Test
    fun getStats_returnsCorrectMap_beforeInitialization() {
        val stats = verifier.getStats()

        assertEquals(false, stats["kernel_exists"])
        assertEquals(false, stats["is_locked_down"])
        assertEquals(0L, stats["last_verification"])
        assertEquals(0, stats["consecutive_verifications"])
        assertEquals(0, stats["axiom_count"])
        assertEquals(0, stats["limit_count"])
    }

    @Test
    fun getStats_returnsCorrectMap_afterInitialization() {
        verifier.initializeKernel(
            listOf("Axiom 1", "Axiom 2", "Axiom 3"),
            listOf("Limit 1", "Limit 2")
        )
        verifier.verify()

        val stats = verifier.getStats()

        assertEquals(true, stats["kernel_exists"])
        assertEquals(false, stats["is_locked_down"])
        assertEquals(1, stats["consecutive_verifications"])
        assertEquals(3, stats["axiom_count"])
        assertEquals(2, stats["limit_count"])
        assertTrue((stats["last_verification"] as Long) > 0L)
    }
}
