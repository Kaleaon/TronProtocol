package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SendPolicyTest {

    private lateinit var policy: SendPolicy

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        policy = SendPolicy(context)
    }

    // --- Mode behavior ---

    @Test
    fun testDisabledModeBlocksAll() {
        policy.setMode(SendPolicy.SendMode.DISABLED)
        val decision = policy.evaluateSend("telegram_bridge", "12345", isReply = false)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("DISABLED"))

        val replyDecision = policy.evaluateSend("telegram_bridge", "12345", isReply = true)
        assertFalse(replyDecision.allowed)
    }

    @Test
    fun testReplyOnlyAllowsReplies() {
        policy.setMode(SendPolicy.SendMode.REPLY_ONLY)
        val reply = policy.evaluateSend("telegram_bridge", "12345", isReply = true)
        assertTrue(reply.allowed)

        val proactive = policy.evaluateSend("telegram_bridge", "12345", isReply = false)
        assertFalse(proactive.allowed)
        assertTrue(proactive.reason.contains("REPLY_ONLY"))
    }

    @Test
    fun testUnrestrictedAllowsAll() {
        policy.setMode(SendPolicy.SendMode.UNRESTRICTED)
        val decision = policy.evaluateSend("telegram_bridge", "12345", isReply = false)
        assertTrue(decision.allowed)
    }

    @Test
    fun testDefaultModeIsUnrestricted() {
        val context = RuntimeEnvironment.getApplication()
        val freshPolicy = SendPolicy(context)
        assertEquals(SendPolicy.SendMode.UNRESTRICTED, freshPolicy.getMode())
    }

    // --- Rate limiting ---

    @Test
    fun testRateLimitedAllowsRepliesUnlimited() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)
        // Replies should always be allowed regardless of rate limits
        repeat(20) {
            val decision = policy.evaluateSend("telegram_bridge", "12345", isReply = true)
            assertTrue("Reply #$it should be allowed", decision.allowed)
        }
    }

    @Test
    fun testRateLimitedEnforcesPerChatLimit() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)

        // Send up to the per-chat limit
        repeat(SendPolicy.MAX_PER_CHAT_PER_HOUR) { i ->
            val decision = policy.evaluateSend("telegram_bridge", "chat1", isReply = false)
            assertTrue("Send #$i should be allowed", decision.allowed)
            policy.recordSend("telegram_bridge", "chat1")
            // Wait past cooldown for next test (simulated by internal rate tracking)
        }

        // Next send should be rate limited (either by per-chat limit or cooldown)
        val decision = policy.evaluateSend("telegram_bridge", "chat1", isReply = false)
        assertFalse("Should be rate limited after ${SendPolicy.MAX_PER_CHAT_PER_HOUR} sends", decision.allowed)
    }

    @Test
    fun testRateLimitedEnforcesCooldown() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)

        // First send: allowed
        val first = policy.evaluateSend("telegram_bridge", "chat1", isReply = false)
        assertTrue(first.allowed)
        policy.recordSend("telegram_bridge", "chat1")

        // Immediate second send: should be blocked by cooldown
        val second = policy.evaluateSend("telegram_bridge", "chat1", isReply = false)
        assertFalse("Should be blocked by cooldown", second.allowed)
        assertTrue(second.cooldownRemainingMs > 0)
    }

    @Test
    fun testRateLimitedDifferentChatsIndependent() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)

        // Send to chat1
        val chat1 = policy.evaluateSend("telegram_bridge", "chat1", isReply = false)
        assertTrue(chat1.allowed)
        policy.recordSend("telegram_bridge", "chat1")

        // Immediately send to chat2 — should be allowed (different chat, no cooldown)
        val chat2 = policy.evaluateSend("telegram_bridge", "chat2", isReply = false)
        assertTrue("Different chat should not be affected by chat1 cooldown", chat2.allowed)
    }

    // --- Stats ---

    @Test
    fun testStatsTracking() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)

        policy.evaluateSend("telegram_bridge", "12345", isReply = true)
        policy.evaluateSend("telegram_bridge", "12345", isReply = true)

        val stats = policy.getStats()
        assertEquals(SendPolicy.SendMode.RATE_LIMITED.name, stats["mode"])
        assertTrue(stats.containsKey("total_allowed"))
        assertTrue(stats.containsKey("total_denied"))
    }

    // --- Mode persistence ---

    @Test
    fun testModeIsPersisted() {
        policy.setMode(SendPolicy.SendMode.RATE_LIMITED)

        val context = RuntimeEnvironment.getApplication()
        val freshPolicy = SendPolicy(context)
        assertEquals(SendPolicy.SendMode.RATE_LIMITED, freshPolicy.getMode())
    }
}
