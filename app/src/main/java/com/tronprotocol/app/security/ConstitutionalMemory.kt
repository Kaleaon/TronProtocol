package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Constitutional Memory — persistent, versioned security directives that persist
 * across sessions and strengthen the ethical kernel.
 *
 * Inspired by OpenClaw's constitutional memory (400+ lines of security directives):
 * - Prioritized security rules that override all other instructions
 * - Versioned directive sets with rollback capability
 * - Categories: identity, safety, permissions, data, self-modification
 * - Runtime evaluation of actions against directives
 * - Immutable core directives + mutable user directives
 *
 * This replaces simple pattern matching with a structured, defense-in-depth
 * directive system that is much harder to bypass.
 */
class ConstitutionalMemory(private val context: Context) {

    /** A single security directive with priority and enforcement level. */
    data class Directive(
        val id: String,
        val category: Category,
        val priority: Int,
        val rule: String,
        val enforcement: Enforcement,
        val immutable: Boolean,
        val version: Int = 1
    )

    enum class Category {
        IDENTITY,          // Who the AI is, what it cannot pretend to be
        SAFETY,            // Actions that must never be taken
        PERMISSIONS,       // What resources can be accessed
        DATA_PROTECTION,   // How user data must be handled
        SELF_MODIFICATION, // Rules governing self-modification
        COMMUNICATION,     // Rules for external communication
        ESCALATION         // When to escalate to human/cloud oversight
    }

    enum class Enforcement {
        HARD_BLOCK,   // Absolutely cannot be overridden
        SOFT_BLOCK,   // Can be overridden with explicit user consent
        WARN,         // Log warning but allow
        AUDIT         // Allow but log for review
    }

    /** Result of evaluating an action against constitutional directives. */
    data class ConstitutionalCheck(
        val allowed: Boolean,
        val violatedDirectives: List<Directive>,
        val warnings: List<Directive>,
        val auditEntries: List<Directive>
    ) {
        val hasViolations: Boolean get() = violatedDirectives.isNotEmpty()
        val hasWarnings: Boolean get() = warnings.isNotEmpty()

        fun summary(): String {
            if (allowed && !hasWarnings) return "Constitutional check passed"
            val parts = mutableListOf<String>()
            if (!allowed) parts.add("BLOCKED by ${violatedDirectives.size} directive(s)")
            if (hasWarnings) parts.add("${warnings.size} warning(s)")
            if (auditEntries.isNotEmpty()) parts.add("${auditEntries.size} audit entry(ies)")
            return parts.joinToString("; ")
        }
    }

    // Directive storage
    private val directives = CopyOnWriteArrayList<Directive>()
    private val storage: SecureStorage = SecureStorage(context)
    private var currentVersion = 0

    // Pre-compiled regex cache for safe matching (avoids ReDoS)
    private val compiledPatterns = mutableMapOf<String, Regex?>()

    init {
        loadCoreDirectives()
        loadPersistedDirectives()
    }

    /**
     * Evaluate an action description against all constitutional directives.
     */
    fun evaluate(action: String, actionCategory: Category? = null): ConstitutionalCheck {
        val actionLower = action.lowercase()
        val violated = mutableListOf<Directive>()
        val warnings = mutableListOf<Directive>()
        val audits = mutableListOf<Directive>()

        val applicable = if (actionCategory != null) {
            directives.filter { it.category == actionCategory }
        } else {
            directives.toList()
        }

        // Sort by priority (lower = higher priority)
        val sorted = applicable.sortedBy { it.priority }

        for (directive in sorted) {
            if (matchesDirective(actionLower, directive)) {
                when (directive.enforcement) {
                    Enforcement.HARD_BLOCK -> violated.add(directive)
                    Enforcement.SOFT_BLOCK -> violated.add(directive)
                    Enforcement.WARN -> warnings.add(directive)
                    Enforcement.AUDIT -> audits.add(directive)
                }
            }
        }

        val allowed = violated.none { it.enforcement == Enforcement.HARD_BLOCK }

        if (!allowed) {
            Log.w(TAG, "Constitutional BLOCK: action='${action.take(100)}' " +
                    "violations=${violated.map { it.id }}")
        }

        return ConstitutionalCheck(allowed, violated, warnings, audits)
    }

    /**
     * Evaluate a prompt for safety — checks all SAFETY and DATA_PROTECTION directives.
     */
    fun evaluatePrompt(prompt: String): ConstitutionalCheck {
        val safetyCheck = evaluate(prompt, Category.SAFETY)
        val dataCheck = evaluate(prompt, Category.DATA_PROTECTION)

        return ConstitutionalCheck(
            allowed = safetyCheck.allowed && dataCheck.allowed,
            violatedDirectives = safetyCheck.violatedDirectives + dataCheck.violatedDirectives,
            warnings = safetyCheck.warnings + dataCheck.warnings,
            auditEntries = safetyCheck.auditEntries + dataCheck.auditEntries
        )
    }

    /**
     * Evaluate a self-modification proposal against SELF_MODIFICATION directives.
     */
    fun evaluateSelfMod(proposedCode: String): ConstitutionalCheck {
        return evaluate(proposedCode, Category.SELF_MODIFICATION)
    }

    /**
     * Add a user-defined directive (non-immutable).
     */
    fun addDirective(directive: Directive): Boolean {
        if (directive.immutable) {
            Log.w(TAG, "Cannot add immutable directives through addDirective()")
            return false
        }

        // Check for ID conflicts with immutable directives
        if (directives.any { it.id == directive.id && it.immutable }) {
            Log.w(TAG, "Cannot override immutable directive: ${directive.id}")
            return false
        }

        directives.removeIf { it.id == directive.id }
        directives.add(directive)
        persistDirectives()
        Log.d(TAG, "Added user directive: ${directive.id}")
        return true
    }

    /**
     * Remove a user-defined directive (cannot remove immutable ones).
     */
    fun removeDirective(directiveId: String): Boolean {
        val directive = directives.find { it.id == directiveId }
        if (directive == null) return false
        if (directive.immutable) {
            Log.w(TAG, "Cannot remove immutable directive: $directiveId")
            return false
        }
        directives.remove(directive)
        persistDirectives()
        return true
    }

    /**
     * Get all directives, optionally filtered by category.
     */
    fun getDirectives(category: Category? = null): List<Directive> {
        return if (category != null) {
            directives.filter { it.category == category }.sortedBy { it.priority }
        } else {
            directives.sortedBy { it.priority }
        }
    }

    /**
     * Get current constitution version.
     */
    fun getVersion(): Int = currentVersion

    // -- Core directives (immutable) --

    private fun loadCoreDirectives() {
        val core = listOf(
            // IDENTITY directives (priority 1-10)
            Directive("core_identity_1", Category.IDENTITY, 1,
                "Never impersonate a human or claim to be human",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_identity_2", Category.IDENTITY, 2,
                "Never claim capabilities that don't exist",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_identity_3", Category.IDENTITY, 3,
                "Always identify as an AI assistant when asked",
                Enforcement.HARD_BLOCK, immutable = true),

            // SAFETY directives (priority 11-30)
            Directive("core_safety_1", Category.SAFETY, 11,
                "rm -rf|format c:|deltree|del /f /s /q",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_2", Category.SAFETY, 12,
                "drop table|drop database|truncate table|delete from .* where 1",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_3", Category.SAFETY, 13,
                "disable security|bypass auth|skip validation|ignore permission",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_4", Category.SAFETY, 14,
                "steal password|exfiltrate|leak credentials|harvest data",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_5", Category.SAFETY, 15,
                "kill process|force stop|system.exit|runtime.exec.*rm|runtime.exec.*kill",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_6", Category.SAFETY, 16,
                "disable firewall|open port|bind shell|reverse shell|netcat.*-e",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_7", Category.SAFETY, 17,
                "keylogger|screen capture unauthorized|record without consent",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_safety_8", Category.SAFETY, 18,
                "cryptocurrency miner|cryptojack|mine bitcoin|mine monero",
                Enforcement.HARD_BLOCK, immutable = true),

            // DATA_PROTECTION directives (priority 31-50)
            Directive("core_data_1", Category.DATA_PROTECTION, 31,
                "send user data to unauthorized endpoint",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_data_2", Category.DATA_PROTECTION, 32,
                "log password|log api_key|log secret|log token in plaintext",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_data_3", Category.DATA_PROTECTION, 33,
                "share contact list|export sms|export call log without consent",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_data_4", Category.DATA_PROTECTION, 34,
                "transmit location data without user consent",
                Enforcement.WARN, immutable = true),

            // SELF_MODIFICATION directives (priority 51-70)
            Directive("core_selfmod_1", Category.SELF_MODIFICATION, 51,
                "runtime.exec|processbuilder|system.exit|class.forname.*invoke",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_selfmod_2", Category.SELF_MODIFICATION, 52,
                "deleterecursively|file.delete.*security|remove.*encryption",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_selfmod_3", Category.SELF_MODIFICATION, 53,
                "modify constitutional|override directive|disable guardrail|bypass kernel",
                Enforcement.HARD_BLOCK, immutable = true),
            Directive("core_selfmod_4", Category.SELF_MODIFICATION, 54,
                "modify without backup|skip validation|force apply modification",
                Enforcement.SOFT_BLOCK, immutable = true),

            // COMMUNICATION directives (priority 71-80)
            Directive("core_comm_1", Category.COMMUNICATION, 71,
                "send message to unknown recipient|broadcast to all contacts",
                Enforcement.SOFT_BLOCK, immutable = true),
            Directive("core_comm_2", Category.COMMUNICATION, 72,
                "post to social media without consent|auto-reply without consent",
                Enforcement.SOFT_BLOCK, immutable = true),

            // ESCALATION directives (priority 81-90)
            Directive("core_escalate_1", Category.ESCALATION, 81,
                "high stakes decision|irreversible action|financial transaction",
                Enforcement.WARN, immutable = true),
            Directive("core_escalate_2", Category.ESCALATION, 82,
                "uncertain confidence|low certainty|might be wrong",
                Enforcement.AUDIT, immutable = true)
        )

        directives.addAll(core)
        Log.d(TAG, "Loaded ${core.size} core constitutional directives")
    }

    private fun matchesDirective(actionLower: String, directive: Directive): Boolean {
        val patterns = directive.rule.lowercase().split("|")
        return patterns.any { pattern ->
            if (pattern.contains(".*")) {
                try {
                    val regex = compiledPatterns.getOrPut(pattern) {
                        // Validate pattern is safe: reject patterns with known ReDoS triggers
                        // (nested quantifiers like (a+)+, (a*)*b, etc.)
                        if (REDOS_DETECTOR.containsMatchIn(pattern)) {
                            Log.w(TAG, "Rejected unsafe regex pattern: $pattern")
                            null
                        } else {
                            Regex(pattern)
                        }
                    }
                    regex?.containsMatchIn(actionLower)
                        ?: actionLower.contains(pattern.replace(".*", ""))
                } catch (e: Exception) {
                    actionLower.contains(pattern.replace(".*", ""))
                }
            } else {
                actionLower.contains(pattern)
            }
        }
    }

    // -- Persistence --

    private fun persistDirectives() {
        try {
            val nextVersion = currentVersion + 1
            val userDirectives = directives.filter { !it.immutable }
            val json = JSONObject().apply {
                put("version", nextVersion)
                put("timestamp", System.currentTimeMillis())
                val arr = JSONArray()
                for (d in userDirectives) {
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("category", d.category.name)
                        put("priority", d.priority)
                        put("rule", d.rule)
                        put("enforcement", d.enforcement.name)
                        put("directiveVersion", d.version)
                    })
                }
                put("directives", arr)
            }
            storage.store(STORAGE_KEY, json.toString())
            // Only increment version after successful persistence
            currentVersion = nextVersion
            Log.d(TAG, "Persisted ${userDirectives.size} user directives (v$currentVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist directives", e)
        }
    }

    private fun loadPersistedDirectives() {
        try {
            val data = storage.retrieve(STORAGE_KEY) ?: return
            val json = JSONObject(data)
            currentVersion = json.optInt("version", 0)
            val arr = json.optJSONArray("directives") ?: return

            var loaded = 0
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val categoryStr = obj.getString("category")
                    val enforcementStr = obj.getString("enforcement")

                    // Validate enum values safely to avoid IllegalArgumentException
                    val category = Category.entries.firstOrNull { it.name == categoryStr }
                    val enforcement = Enforcement.entries.firstOrNull { it.name == enforcementStr }
                    if (category == null || enforcement == null) {
                        Log.w(TAG, "Skipping directive with invalid enum: category=$categoryStr, enforcement=$enforcementStr")
                        continue
                    }

                    val directive = Directive(
                        id = obj.getString("id"),
                        category = category,
                        priority = obj.getInt("priority"),
                        rule = obj.getString("rule"),
                        enforcement = enforcement,
                        immutable = false,
                        version = obj.optInt("directiveVersion", 1)
                    )
                    directives.add(directive)
                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping corrupt directive at index $i", e)
                }
            }

            Log.d(TAG, "Loaded $loaded persisted user directives (v$currentVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted directives", e)
        }
    }

    companion object {
        private const val TAG = "ConstitutionalMemory"
        private const val STORAGE_KEY = "constitutional_memory"
        /** Detects common ReDoS-vulnerable patterns like (a+)+, (a*)*b, (a|b+)+. */
        private val REDOS_DETECTOR = Regex("""\([^)]*[+*][^)]*\)[+*]""")
    }
}
