package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Audit Logger — comprehensive activity tracking with async/sync modes.
 *
 * Inspired by OpenClaw's audit system (audit.ts, audit-extra.ts, audit-fs.ts):
 * - Logs all significant actions (plugin executions, API calls, self-modifications)
 * - Async mode for non-blocking logging during normal operation
 * - Sync mode for critical security events that must be recorded immediately
 * - Structured audit entries with timestamps, actors, actions, and outcomes
 * - Queryable audit trail for forensic analysis
 * - Automatic rotation and size management
 * - Integration with ConstitutionalMemory for violation logging
 *
 * This provides the observability layer that TronProtocol was previously missing.
 */
class AuditLogger(private val context: Context) {

    /** Severity levels for audit entries. */
    enum class Severity { DEBUG, INFO, WARNING, ERROR, CRITICAL }

    /** Categories of auditable events. */
    enum class AuditCategory {
        PLUGIN_EXECUTION,
        API_CALL,
        SELF_MODIFICATION,
        SECURITY_EVENT,
        CONSTITUTIONAL_VIOLATION,
        PERMISSION_CHANGE,
        DATA_ACCESS,
        SYSTEM_LIFECYCLE,
        SUB_AGENT,
        MEMORY_OPERATION,
        FAILOVER_EVENT,
        POLICY_DECISION,
        MODEL_INTEGRITY
    }

    /** A single audit log entry. */
    data class AuditEntry(
        val id: Long,
        val timestamp: Long,
        val severity: Severity,
        val category: AuditCategory,
        val actor: String,
        val action: String,
        val target: String?,
        val outcome: String,
        val details: Map<String, Any>?,
        val sessionId: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("datetime", ISO_FORMAT.get()!!.format(Date(timestamp)))
            put("severity", severity.name)
            put("category", category.name)
            put("actor", actor)
            put("action", action)
            put("target", target ?: "")
            put("outcome", outcome)
            if (details != null) {
                val detailsObj = JSONObject()
                for ((key, value) in details) {
                    detailsObj.put(key, value)
                }
                put("details", detailsObj)
            }
            put("session_id", sessionId ?: "")
        }
    }

    // Entry storage
    private val entries = ConcurrentLinkedQueue<AuditEntry>()
    private val entryCounter = AtomicLong(0)
    private var currentSessionId: String? = null

    // Async writer
    private val asyncQueue = ConcurrentLinkedQueue<AuditEntry>()
    private val asyncWriter: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AuditLogger-Writer").apply { isDaemon = true }
    }

    // Persistent storage
    private val storage: SecureStorage = SecureStorage(context)

    init {
        loadPersistedEntries()
        // Start async flush loop
        asyncWriter.execute { asyncFlushLoop() }
    }

    /**
     * Set the current session ID for audit correlation.
     */
    fun setSessionId(sessionId: String) {
        this.currentSessionId = sessionId
    }

    /**
     * Log an audit entry asynchronously (non-blocking).
     */
    fun logAsync(
        severity: Severity,
        category: AuditCategory,
        actor: String,
        action: String,
        target: String? = null,
        outcome: String = "success",
        details: Map<String, Any>? = null
    ) {
        val entry = createEntry(severity, category, actor, action, target, outcome, details)
        asyncQueue.add(entry)
        entries.add(entry)
        trimIfNeeded()
    }

    /**
     * Log an audit entry synchronously (blocking, for critical events).
     */
    fun logSync(
        severity: Severity,
        category: AuditCategory,
        actor: String,
        action: String,
        target: String? = null,
        outcome: String = "success",
        details: Map<String, Any>? = null
    ) {
        val entry = createEntry(severity, category, actor, action, target, outcome, details)
        entries.add(entry)
        trimIfNeeded()
        persistEntry(entry)

        if (severity == Severity.CRITICAL || severity == Severity.ERROR) {
            Log.w(TAG, "AUDIT [${severity.name}] ${category.name}: $actor -> $action " +
                    "(target=$target outcome=$outcome)")
        }
    }

    // -- Convenience methods for common audit events --

    fun logPluginExecution(pluginId: String, input: String, success: Boolean, durationMs: Long) {
        logAsync(
            severity = if (success) Severity.INFO else Severity.WARNING,
            category = AuditCategory.PLUGIN_EXECUTION,
            actor = pluginId,
            action = "execute",
            outcome = if (success) "success" else "failure",
            details = mapOf(
                "input_length" to input.length,
                "duration_ms" to durationMs
            )
        )
    }

    fun logApiCall(model: String, success: Boolean, latencyMs: Long, failoverReason: String? = null) {
        logAsync(
            severity = if (success) Severity.INFO else Severity.WARNING,
            category = AuditCategory.API_CALL,
            actor = "anthropic_client",
            action = "create_guidance",
            target = model,
            outcome = if (success) "success" else "failure",
            details = buildMap {
                put("latency_ms", latencyMs)
                if (failoverReason != null) put("failover_reason", failoverReason)
            }
        )
    }

    fun logSecurityEvent(actor: String, action: String, outcome: String, details: Map<String, Any>? = null) {
        logSync(
            severity = if (outcome == "blocked") Severity.ERROR else Severity.WARNING,
            category = AuditCategory.SECURITY_EVENT,
            actor = actor,
            action = action,
            outcome = outcome,
            details = details
        )
    }

    fun logConstitutionalViolation(
        actor: String,
        action: String,
        directiveId: String,
        rule: String
    ) {
        logSync(
            severity = Severity.CRITICAL,
            category = AuditCategory.CONSTITUTIONAL_VIOLATION,
            actor = actor,
            action = action,
            outcome = "blocked",
            details = mapOf(
                "directive_id" to directiveId,
                "rule" to rule
            )
        )
    }

    fun logSelfModification(action: String, success: Boolean, details: Map<String, Any>? = null) {
        logSync(
            severity = if (success) Severity.INFO else Severity.WARNING,
            category = AuditCategory.SELF_MODIFICATION,
            actor = "code_mod_manager",
            action = action,
            outcome = if (success) "success" else "failure",
            details = details
        )
    }



    fun logModelIntegrityVerification(
        modelId: String,
        success: Boolean,
        details: Map<String, Any>
    ) {
        logSync(
            severity = if (success) Severity.INFO else Severity.ERROR,
            category = AuditCategory.MODEL_INTEGRITY,
            actor = "on_device_llm",
            action = "verify_model_integrity",
            target = modelId,
            outcome = if (success) "verified" else "blocked",
            details = details
        )
    }

    fun logSubAgent(agentId: String, parentPlugin: String, targetPlugin: String,
                    status: String, runtimeMs: Long) {
        logAsync(
            severity = Severity.INFO,
            category = AuditCategory.SUB_AGENT,
            actor = parentPlugin,
            action = "spawn",
            target = targetPlugin,
            outcome = status,
            details = mapOf(
                "agent_id" to agentId,
                "runtime_ms" to runtimeMs
            )
        )
    }

    // -- Query methods --

    /**
     * Query audit entries by category.
     */
    fun query(
        category: AuditCategory? = null,
        severity: Severity? = null,
        actor: String? = null,
        limit: Int = 100
    ): List<AuditEntry> {
        return entries.filter { entry ->
            (category == null || entry.category == category) &&
            (severity == null || entry.severity == severity) &&
            (actor == null || entry.actor == actor)
        }.takeLast(limit)
    }

    /**
     * Get security-relevant entries (WARNING and above).
     */
    fun getSecurityAuditTrail(limit: Int = 50): List<AuditEntry> {
        return entries.filter { it.severity >= Severity.WARNING }
            .takeLast(limit)
    }

    /**
     * Get aggregate statistics.
     */
    fun getStats(): Map<String, Any> {
        val entriesList = entries.toList()
        return mapOf(
            "total_entries" to entriesList.size,
            "by_severity" to Severity.entries.associate { s ->
                s.name to entriesList.count { it.severity == s }
            },
            "by_category" to AuditCategory.entries.associate { c ->
                c.name to entriesList.count { it.category == c }
            },
            "critical_count" to entriesList.count { it.severity == Severity.CRITICAL },
            "session_id" to (currentSessionId ?: "none")
        )
    }

    /**
     * Export audit trail as JSON.
     */
    fun exportJson(limit: Int = 500): String {
        val arr = JSONArray()
        entries.toList().takeLast(limit).forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    /**
     * Shut down the audit logger, flushing remaining entries.
     */
    fun shutdown() {
        flushAsyncQueue()
        asyncWriter.shutdown()
        try {
            asyncWriter.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            asyncWriter.shutdownNow()
        }
        persistAllEntries()
        Log.d(TAG, "AuditLogger shut down. Total entries: ${entries.size}")
    }

    // -- Internal --

    private fun createEntry(
        severity: Severity,
        category: AuditCategory,
        actor: String,
        action: String,
        target: String?,
        outcome: String,
        details: Map<String, Any>?
    ): AuditEntry {
        return AuditEntry(
            id = entryCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            severity = severity,
            category = category,
            actor = actor,
            action = action,
            target = target,
            outcome = outcome,
            details = details,
            sessionId = currentSessionId
        )
    }

    private fun trimIfNeeded() {
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
    }

    private fun asyncFlushLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS)
                flushAsyncQueue()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun flushAsyncQueue() {
        val batch = mutableListOf<AuditEntry>()
        while (true) {
            val entry = asyncQueue.poll() ?: break
            batch.add(entry)
        }
        if (batch.isNotEmpty()) {
            persistBatch(batch)
        }
    }

    private fun persistEntry(entry: AuditEntry) {
        persistBatch(listOf(entry))
    }

    private fun persistBatch(batch: List<AuditEntry>) {
        try {
            val existing = loadPersistedJson()
            for (entry in batch) {
                existing.put(entry.toJson())
            }
            // Keep only last MAX_PERSISTED entries
            while (existing.length() > MAX_PERSISTED) {
                existing.remove(0)
            }
            storage.store(STORAGE_KEY, existing.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist audit batch (${batch.size} entries)", e)
        }
    }

    private fun persistAllEntries() {
        try {
            val arr = JSONArray()
            entries.toList().takeLast(MAX_PERSISTED).forEach { arr.put(it.toJson()) }
            storage.store(STORAGE_KEY, arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist all audit entries", e)
        }
    }

    private fun loadPersistedJson(): JSONArray {
        return try {
            val data = storage.retrieve(STORAGE_KEY) ?: return JSONArray()
            JSONArray(data)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun loadPersistedEntries() {
        try {
            val arr = loadPersistedJson()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val entry = AuditEntry(
                    id = obj.getLong("id"),
                    timestamp = obj.getLong("timestamp"),
                    severity = Severity.valueOf(obj.getString("severity")),
                    category = AuditCategory.valueOf(obj.getString("category")),
                    actor = obj.getString("actor"),
                    action = obj.getString("action"),
                    target = obj.optString("target", null),
                    outcome = obj.getString("outcome"),
                    details = null,
                    sessionId = obj.optString("session_id", null)
                )
                entries.add(entry)
                entryCounter.set(maxOf(entryCounter.get(), entry.id))
            }
            Log.d(TAG, "Loaded ${arr.length()} persisted audit entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted audit entries", e)
        }
    }

    companion object {
        private const val TAG = "AuditLogger"
        private const val STORAGE_KEY = "audit_log"
        private const val MAX_ENTRIES = 5000
        private const val MAX_PERSISTED = 2000
        private const val FLUSH_INTERVAL_MS = 10_000L

        val ISO_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        }
    }
}
