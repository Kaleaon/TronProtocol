package com.tronprotocol.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditLoggerTest {

    private lateinit var logger: AuditLogger
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Clear any persisted audit data from previous test runs
        val storage = SecureStorage(context)
        storage.clearAll()
        logger = AuditLogger(context)
    }

    @After
    fun tearDown() {
        logger.shutdown()
    }

    // --- logSync creates entry with correct fields ---

    @Test
    fun logSync_createsEntryWithCorrectFields() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "test_plugin",
            action = "execute",
            target = "some_target",
            outcome = "success",
            details = mapOf("key" to "value")
        )

        val entries = logger.query(category = AuditLogger.AuditCategory.PLUGIN_EXECUTION)
        assertEquals(1, entries.size)

        val entry = entries[0]
        assertEquals(AuditLogger.Severity.INFO, entry.severity)
        assertEquals(AuditLogger.AuditCategory.PLUGIN_EXECUTION, entry.category)
        assertEquals("test_plugin", entry.actor)
        assertEquals("execute", entry.action)
        assertEquals("some_target", entry.target)
        assertEquals("success", entry.outcome)
        assertNotNull(entry.details)
        assertEquals("value", entry.details!!["key"])
        assertTrue(entry.timestamp > 0)
        assertTrue(entry.id > 0)
    }

    // --- logAsync creates entry ---

    @Test
    fun logAsync_createsEntry() {
        logger.logAsync(
            severity = AuditLogger.Severity.DEBUG,
            category = AuditLogger.AuditCategory.DATA_ACCESS,
            actor = "async_actor",
            action = "read_data"
        )

        // logAsync adds to entries immediately (async is only for persistence)
        val entries = logger.query(category = AuditLogger.AuditCategory.DATA_ACCESS)
        assertEquals(1, entries.size)
        assertEquals("async_actor", entries[0].actor)
        assertEquals("read_data", entries[0].action)
    }

    // --- Query by category ---

    @Test
    fun query_byCategory_returnsMatchingEntries() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "plugin_a",
            action = "execute"
        )
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.API_CALL,
            actor = "api_client",
            action = "call"
        )
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "plugin_b",
            action = "execute"
        )

        val pluginEntries = logger.query(category = AuditLogger.AuditCategory.PLUGIN_EXECUTION)
        assertEquals(2, pluginEntries.size)
        assertTrue(pluginEntries.all { it.category == AuditLogger.AuditCategory.PLUGIN_EXECUTION })
    }

    // --- Query by severity ---

    @Test
    fun query_bySeverity_filtersCorrectly() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
            actor = "system",
            action = "start"
        )
        logger.logSync(
            severity = AuditLogger.Severity.WARNING,
            category = AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
            actor = "system",
            action = "warn_event"
        )
        logger.logSync(
            severity = AuditLogger.Severity.ERROR,
            category = AuditLogger.AuditCategory.SECURITY_EVENT,
            actor = "system",
            action = "error_event"
        )

        val warnings = logger.query(severity = AuditLogger.Severity.WARNING)
        assertEquals(1, warnings.size)
        assertEquals("warn_event", warnings[0].action)
    }

    // --- Query by actor ---

    @Test
    fun query_byActor_filtersCorrectly() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "plugin_alpha",
            action = "run"
        )
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "plugin_beta",
            action = "run"
        )
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "plugin_alpha",
            action = "stop"
        )

        val alphaEntries = logger.query(actor = "plugin_alpha")
        assertEquals(2, alphaEntries.size)
        assertTrue(alphaEntries.all { it.actor == "plugin_alpha" })
    }

    // --- getSecurityAuditTrail returns WARNING+ entries ---

    @Test
    fun getSecurityAuditTrail_returnsWarningAndAbove() {
        logger.logSync(
            severity = AuditLogger.Severity.DEBUG,
            category = AuditLogger.AuditCategory.DATA_ACCESS,
            actor = "debug_actor",
            action = "debug_action"
        )
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.DATA_ACCESS,
            actor = "info_actor",
            action = "info_action"
        )
        logger.logSync(
            severity = AuditLogger.Severity.WARNING,
            category = AuditLogger.AuditCategory.SECURITY_EVENT,
            actor = "warn_actor",
            action = "warn_action"
        )
        logger.logSync(
            severity = AuditLogger.Severity.ERROR,
            category = AuditLogger.AuditCategory.SECURITY_EVENT,
            actor = "error_actor",
            action = "error_action"
        )
        logger.logSync(
            severity = AuditLogger.Severity.CRITICAL,
            category = AuditLogger.AuditCategory.CONSTITUTIONAL_VIOLATION,
            actor = "critical_actor",
            action = "critical_action"
        )

        val trail = logger.getSecurityAuditTrail()
        assertEquals(3, trail.size)
        assertTrue(trail.all { it.severity >= AuditLogger.Severity.WARNING })
    }

    // --- getStats returns correct counts ---

    @Test
    fun getStats_returnsCorrectCounts() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "p1",
            action = "run"
        )
        logger.logSync(
            severity = AuditLogger.Severity.CRITICAL,
            category = AuditLogger.AuditCategory.CONSTITUTIONAL_VIOLATION,
            actor = "cv",
            action = "block"
        )

        val stats = logger.getStats()
        assertEquals(2, stats["total_entries"])
        assertEquals(1, stats["critical_count"])

        @Suppress("UNCHECKED_CAST")
        val bySeverity = stats["by_severity"] as Map<String, Int>
        assertEquals(1, bySeverity["INFO"])
        assertEquals(1, bySeverity["CRITICAL"])
        assertEquals(0, bySeverity["WARNING"])
    }

    // --- logPluginExecution creates INFO for success, WARNING for failure ---

    @Test
    fun logPluginExecution_createsInfoForSuccess() {
        logger.logPluginExecution("calc", "2+3", success = true, durationMs = 50)

        val entries = logger.query(category = AuditLogger.AuditCategory.PLUGIN_EXECUTION)
        assertEquals(1, entries.size)
        assertEquals(AuditLogger.Severity.INFO, entries[0].severity)
        assertEquals("success", entries[0].outcome)
        assertEquals("calc", entries[0].actor)
    }

    @Test
    fun logPluginExecution_createsWarningForFailure() {
        logger.logPluginExecution("calc", "bad_input", success = false, durationMs = 10)

        val entries = logger.query(category = AuditLogger.AuditCategory.PLUGIN_EXECUTION)
        assertEquals(1, entries.size)
        assertEquals(AuditLogger.Severity.WARNING, entries[0].severity)
        assertEquals("failure", entries[0].outcome)
    }

    // --- logSecurityEvent creates ERROR for blocked ---

    @Test
    fun logSecurityEvent_createsErrorForBlocked() {
        logger.logSecurityEvent(
            actor = "malicious_actor",
            action = "unauthorized_access",
            outcome = "blocked"
        )

        val entries = logger.query(category = AuditLogger.AuditCategory.SECURITY_EVENT)
        assertEquals(1, entries.size)
        assertEquals(AuditLogger.Severity.ERROR, entries[0].severity)
        assertEquals("blocked", entries[0].outcome)
    }

    // --- logConstitutionalViolation creates CRITICAL entry ---

    @Test
    fun logConstitutionalViolation_createsCriticalEntry() {
        logger.logConstitutionalViolation(
            actor = "test_actor",
            action = "rm -rf /",
            directiveId = "core_safety_1",
            rule = "destructive_command"
        )

        val entries = logger.query(category = AuditLogger.AuditCategory.CONSTITUTIONAL_VIOLATION)
        assertEquals(1, entries.size)
        assertEquals(AuditLogger.Severity.CRITICAL, entries[0].severity)
        assertEquals("blocked", entries[0].outcome)
        assertEquals("test_actor", entries[0].actor)
    }

    // --- exportJson returns valid JSON string ---

    @Test
    fun exportJson_returnsValidJsonString() {
        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
            actor = "system",
            action = "start"
        )
        logger.logSync(
            severity = AuditLogger.Severity.WARNING,
            category = AuditLogger.AuditCategory.SECURITY_EVENT,
            actor = "guard",
            action = "alert"
        )

        val json = logger.exportJson()
        val arr = JSONArray(json)
        assertEquals(2, arr.length())

        val first = arr.getJSONObject(0)
        assertTrue(first.has("id"))
        assertTrue(first.has("timestamp"))
        assertTrue(first.has("severity"))
        assertTrue(first.has("category"))
        assertTrue(first.has("actor"))
        assertTrue(first.has("action"))
        assertTrue(first.has("outcome"))
    }

    // --- Entries are trimmed when MAX_ENTRIES exceeded ---

    @Test
    fun entries_areTrimmedWhenMaxExceeded() {
        // MAX_ENTRIES is 5000; add 5010 entries
        for (i in 1..5010) {
            logger.logAsync(
                severity = AuditLogger.Severity.DEBUG,
                category = AuditLogger.AuditCategory.DATA_ACCESS,
                actor = "bulk_actor",
                action = "action_$i"
            )
        }

        val allEntries = logger.query(limit = 10000)
        assertTrue(
            "Entries should be trimmed to at most 5000, but got ${allEntries.size}",
            allEntries.size <= 5000
        )
    }

    // --- AuditEntry.toJson produces valid JSON with all fields ---

    @Test
    fun auditEntry_toJson_producesValidJson() {
        logger.logSync(
            severity = AuditLogger.Severity.ERROR,
            category = AuditLogger.AuditCategory.SECURITY_EVENT,
            actor = "json_test_actor",
            action = "json_test_action",
            target = "json_test_target",
            outcome = "failure",
            details = mapOf("detail_key" to "detail_value")
        )

        val entries = logger.query(actor = "json_test_actor")
        assertEquals(1, entries.size)

        val json = entries[0].toJson()
        assertEquals(entries[0].id, json.getLong("id"))
        assertEquals(entries[0].timestamp, json.getLong("timestamp"))
        assertEquals("ERROR", json.getString("severity"))
        assertEquals("SECURITY_EVENT", json.getString("category"))
        assertEquals("json_test_actor", json.getString("actor"))
        assertEquals("json_test_action", json.getString("action"))
        assertEquals("json_test_target", json.getString("target"))
        assertEquals("failure", json.getString("outcome"))
        assertTrue(json.has("datetime"))
        assertTrue(json.has("details"))
        assertEquals("detail_value", json.getJSONObject("details").getString("detail_key"))
    }

    // --- setSessionId associates entries with session ---

    @Test
    fun setSessionId_associatesEntriesWithSession() {
        logger.setSessionId("session_abc_123")

        logger.logSync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
            actor = "session_actor",
            action = "session_action"
        )

        val entries = logger.query(actor = "session_actor")
        assertEquals(1, entries.size)
        assertEquals("session_abc_123", entries[0].sessionId)
    }
}
