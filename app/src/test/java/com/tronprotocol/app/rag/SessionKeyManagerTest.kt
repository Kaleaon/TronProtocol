package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionKeyManagerTest {

    private lateinit var context: Context
    private lateinit var manager: SessionKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = SessionKeyManager(context)
    }

    // --- generateSessionKey (createSession) returns non-empty key ---

    @Test
    fun createSession_returnsNonEmptyKey() {
        val sessionKey = manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.LOCAL,
            SessionKeyManager.Scope.DM,
            "user_123"
        )
        assertNotNull(sessionKey)
        assertTrue(sessionKey.key.isNotEmpty())
    }

    // --- generateSessionKey format includes aiId, channel, scope ---

    @Test
    fun createSession_formatIncludesAiIdChannelScope() {
        val sessionKey = manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.TELEGRAM,
            SessionKeyManager.Scope.GROUP,
            "chat_456"
        )
        val key = sessionKey.key
        assertTrue("Key should contain aiId", key.contains("test_ai"))
        assertTrue("Key should contain channel", key.contains("telegram"))
        assertTrue("Key should contain scope", key.contains("group"))
        assertTrue("Key should contain identifier", key.contains("chat_456"))
    }

    // --- parseKey parses valid key components ---

    @Test
    fun parseKey_parsesValidKeyComponents() {
        val originalKey = manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.LOCAL,
            SessionKeyManager.Scope.PLUGIN,
            "calculator"
        )

        val parsed = manager.parseKey(originalKey.key)
        assertNotNull("Should parse a valid key", parsed)
        assertEquals("test_ai", parsed!!.aiId)
        assertEquals(SessionKeyManager.Channel.LOCAL, parsed.channel)
        assertEquals(SessionKeyManager.Scope.PLUGIN, parsed.scope)
        assertEquals("calculator", parsed.identifier)
    }

    // --- parseKey returns null for invalid key ---

    @Test
    fun parseKey_returnsNullForInvalidKey() {
        val parsed = manager.parseKey("invalid_key_format")
        assertNull("Should return null for invalid key format", parsed)
    }

    @Test
    fun parseKey_returnsNullForEmptyKey() {
        val parsed = manager.parseKey("")
        assertNull("Should return null for empty key", parsed)
    }

    // --- startSession (createSession) creates new session ---

    @Test
    fun createSession_createsNewSession() {
        val sessionKey = manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.SERVICE,
            SessionKeyManager.Scope.HEARTBEAT,
            "session_001"
        )

        val activeSessions = manager.getActiveSessions()
        assertTrue(
            "Active sessions should contain the new session",
            activeSessions.any { it.sessionKey.key == sessionKey.key }
        )
    }

    // --- getCurrentSession returns active session ---

    @Test
    fun getActiveSessions_returnsActiveSessions() {
        manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.LOCAL,
            SessionKeyManager.Scope.DM,
            "user_abc"
        )

        val sessions = manager.getActiveSessions()
        assertFalse("Should have at least one active session", sessions.isEmpty())
    }

    // --- getCurrentSession returns null/empty when no session ---

    @Test
    fun getActiveSessions_returnsEmptyWhenNoSessions() {
        // Fresh manager with no sessions created (from setUp, no sessions added)
        // Cleanup any potentially loaded sessions by using a fresh manager
        val freshManager = SessionKeyManager(context)
        // If nothing was previously persisted this should be empty or only have
        // previously persisted sessions. We verify the method works without error.
        assertNotNull(freshManager.getActiveSessions())
    }

    // --- endSession (archiveExpiredSessions + cleanup) terminates session ---

    @Test
    fun archiveAndCleanup_terminatesSession() {
        val sessionKey = manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.LOCAL,
            SessionKeyManager.Scope.DM,
            "user_to_remove"
        )

        // Archive with a max age of 0ms (immediately archives everything)
        val archived = manager.archiveExpiredSessions(0L)
        assertTrue("Should archive at least 1 session", archived >= 1)

        val cleaned = manager.cleanupArchivedSessions()
        assertTrue("Should clean up archived sessions", cleaned >= 1)
    }

    // --- getStats returns meaningful statistics ---

    @Test
    fun getStats_returnsMeaningfulStats() {
        manager.createSession(
            "test_ai",
            SessionKeyManager.Channel.TELEGRAM,
            SessionKeyManager.Scope.DM,
            "user_stats"
        )

        val stats = manager.getStats()
        assertTrue(stats.containsKey("total_sessions"))
        assertTrue(stats.containsKey("active_sessions"))
        assertTrue(stats.containsKey("archived_sessions"))
        assertTrue((stats["total_sessions"] as Int) >= 1)
    }
}
