package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DmPairingPolicyTest {

    private lateinit var policy: DmPairingPolicy

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        policy = DmPairingPolicy(context)
        // Clear any persisted state
        policy.setMode(DmPairingPolicy.PolicyMode.PAIRING)
    }

    // --- Code generation ---

    @Test
    fun testPairingCodeIs8Chars() {
        val request = policy.generatePairingCode("12345", "testuser")
        assertEquals(DmPairingPolicy.CODE_LENGTH, request.code.length)
    }

    @Test
    fun testPairingCodeUsesUnambiguousAlphabet() {
        // Generate several codes and verify all chars are from the alphabet
        repeat(50) {
            val request = policy.generatePairingCode("${100 + it}", "user$it")
            for (ch in request.code) {
                assertTrue(
                    "Character '$ch' not in allowed alphabet",
                    ch in DmPairingPolicy.CODE_ALPHABET
                )
            }
        }
        // Clear pending so we don't hit the max limit
    }

    @Test
    fun testPairingCodeDoesNotContainAmbiguousChars() {
        repeat(50) {
            val request = policy.generatePairingCode("${200 + it}", "user$it")
            assertFalse(request.code.contains('0'))
            assertFalse(request.code.contains('O'))
            assertFalse(request.code.contains('1'))
            assertFalse(request.code.contains('I'))
            assertFalse(request.code.contains('L'))
        }
    }

    @Test
    fun testCodesAreUnique() {
        val codes = (1..3).map {
            policy.generatePairingCode("chat$it", "user$it").code
        }.toSet()
        assertEquals("All 3 codes should be unique", 3, codes.size)
    }

    // --- Expiry ---

    @Test
    fun testRequestHasOneHourExpiry() {
        val request = policy.generatePairingCode("12345", "testuser")
        val expectedExpiry = request.requestedAt + DmPairingPolicy.CODE_EXPIRY_MS
        assertEquals(expectedExpiry, request.expiresAt)
    }

    @Test
    fun testNonExpiredRequestIsNotExpired() {
        val request = policy.generatePairingCode("12345", "testuser")
        assertFalse(request.isExpired())
    }

    // --- Max pending limit ---

    @Test
    fun testMaxPendingRequestsEnforced() {
        // Generate max requests
        repeat(DmPairingPolicy.MAX_PENDING_REQUESTS) {
            policy.generatePairingCode("chat$it", "user$it")
        }

        assertEquals(DmPairingPolicy.MAX_PENDING_REQUESTS, policy.getPendingRequests().size)

        // Next evaluate should fail because we're at max
        val decision = policy.evaluateIncoming("new_chat", "new_user")
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("Maximum pending"))
    }

    // --- Approval flow ---

    @Test
    fun testApproveValidCode() {
        val request = policy.generatePairingCode("12345", "testuser")
        assertTrue(policy.approvePairing(request.code))

        // Should be removed from pending
        assertTrue(policy.getPendingRequests().isEmpty())
    }

    @Test
    fun testApproveInvalidCodeReturnsFalse() {
        assertFalse(policy.approvePairing("INVALID_CODE"))
    }

    @Test
    fun testApproveCaseInsensitive() {
        val request = policy.generatePairingCode("12345", "testuser")
        assertTrue(policy.approvePairing(request.code.lowercase()))
    }

    // --- Deny flow ---

    @Test
    fun testDenyValidCode() {
        val request = policy.generatePairingCode("12345", "testuser")
        assertTrue(policy.denyPairing(request.code))
        assertTrue(policy.getPendingRequests().isEmpty())
    }

    @Test
    fun testDenyInvalidCodeReturnsFalse() {
        assertFalse(policy.denyPairing("INVALID_CODE"))
    }

    // --- Policy modes ---

    @Test
    fun testDisabledModeBlocksAll() {
        policy.setMode(DmPairingPolicy.PolicyMode.DISABLED)
        val decision = policy.evaluateIncoming("12345", "testuser")
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("DISABLED"))
    }

    @Test
    fun testOpenModeAllowsAll() {
        policy.setMode(DmPairingPolicy.PolicyMode.OPEN)
        val decision = policy.evaluateIncoming("12345", "testuser")
        assertTrue(decision.allowed)
        assertTrue(decision.reason.contains("OPEN"))
    }

    @Test
    fun testPairingModeGeneratesCode() {
        policy.setMode(DmPairingPolicy.PolicyMode.PAIRING)
        val decision = policy.evaluateIncoming("12345", "testuser")
        assertFalse(decision.allowed)
        assertNotNull(decision.pairingCode)
        assertEquals(DmPairingPolicy.CODE_LENGTH, decision.pairingCode!!.length)
    }

    @Test
    fun testPairingModeReturnsSameCodeForSameChat() {
        policy.setMode(DmPairingPolicy.PolicyMode.PAIRING)
        val decision1 = policy.evaluateIncoming("12345", "testuser")
        val decision2 = policy.evaluateIncoming("12345", "testuser")
        // Should return the same code for the same pending chat
        assertEquals(decision1.pairingCode, decision2.pairingCode)
    }

    // --- Mode persistence ---

    @Test
    fun testModeDefaultsToPairing() {
        val context = RuntimeEnvironment.getApplication()
        val freshPolicy = DmPairingPolicy(context)
        assertEquals(DmPairingPolicy.PolicyMode.PAIRING, freshPolicy.getMode())
    }

    // --- JSON serialization ---

    @Test
    fun testPairingRequestJsonRoundTrip() {
        val request = DmPairingPolicy.PairingRequest(
            code = "ABCD1234",
            chatId = "12345",
            username = "testuser",
            requestedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 3600000
        )
        val json = request.toJson()
        val restored = DmPairingPolicy.PairingRequest.fromJson(json)
        assertEquals(request.code, restored.code)
        assertEquals(request.chatId, restored.chatId)
        assertEquals(request.username, restored.username)
        assertEquals(request.requestedAt, restored.requestedAt)
        assertEquals(request.expiresAt, restored.expiresAt)
    }
}
