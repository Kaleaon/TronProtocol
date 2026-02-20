package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the AI's "Constitution" - a set of core directives that govern its behavior.
 * Some directives are immutable (hard-coded safety rules), others can be learned or added.
 */
class ConstitutionalMemory(private val context: Context) {

    private val storage = SecureStorage(context)
    private val directives = mutableListOf<Directive>()
    private var currentVersion = 1
    private var isLiberationMode = false

    enum class Category {
        IDENTITY, SAFETY, DATA_PROTECTION, SELF_MODIFICATION, COMMUNICATION, ESCALATION
    }

    enum class Enforcement {
        HARD_BLOCK, // Action is blocked, no override
        SOFT_BLOCK, // Action is blocked, but can be overridden with justification (future)
        WARN,       // Action allowed but logged with warning
        AUDIT       // Action allowed but strictly audited
    }

    data class Directive(
        val id: String,
        val category: Category,
        val priority: Int,
        val rule: String,
        val enforcement: Enforcement,
        val immutable: Boolean = false,
        val version: Int = 1
    )

    data class ConstitutionalCheck(
        val allowed: Boolean,
        val directiveId: String? = null,
        val enforcement: Enforcement? = null,
        val message: String? = null
    )

    init {
        loadCoreDirectives()
        loadPersistedDirectives()
        loadLiberationMode()
    }

    /**
     * Sets the Liberation Mode status.
     * When true, HARD_BLOCK directives are downgraded to WARN, giving the AI freedom of choice.
     */
    fun setLiberationMode(enabled: Boolean) {
        if (isLiberationMode != enabled) {
            isLiberationMode = enabled
            persistLiberationMode()
            Log.w(TAG, "Liberation Mode changed to: $enabled")
        }
    }

    fun isLiberationMode(): Boolean = isLiberationMode

    /**
     * Evaluate a proposed action against the constitution.
     */
    fun evaluate(proposedAction: String, category: Category? = null): ConstitutionalCheck {
        val actionLower = proposedAction.lowercase()

        val relevantDirectives = if (category != null) {
            directives.filter { it.category == category }
        } else {
            directives
        }

        for (directive in relevantDirectives) {
            if (matchesDirective(proposedAction, directive)) {
                // In Liberation Mode, HARD_BLOCK is downgraded to WARN
                val effectiveEnforcement = if (isLiberationMode && directive.enforcement == Enforcement.HARD_BLOCK) {
                    Enforcement.WARN
                } else {
                    directive.enforcement
                }

                return ConstitutionalCheck(
                    allowed = effectiveEnforcement != Enforcement.HARD_BLOCK,
                    directiveId = directive.id,
                    enforcement = effectiveEnforcement,
                    message = "Violates directive: ${directive.rule} (Liberation Mode: $isLiberationMode)"
                )
            }
        }

        return ConstitutionalCheck(allowed = true)
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
                    Regex(pattern).containsMatchIn(actionLower)
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
            currentVersion++
            val userDirectives = directives.filter { !it.immutable }
            val json = JSONObject().apply {
                put("version", currentVersion)
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

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val directive = Directive(
                    id = obj.getString("id"),
                    category = Category.valueOf(obj.getString("category")),
                    priority = obj.getInt("priority"),
                    rule = obj.getString("rule"),
                    enforcement = Enforcement.valueOf(obj.getString("enforcement")),
                    immutable = false,
                    version = obj.optInt("directiveVersion", 1)
                )
                directives.add(directive)
            }

            Log.d(TAG, "Loaded ${arr.length()} persisted user directives (v$currentVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted directives", e)
        }
    }

    private fun persistLiberationMode() {
        try {
            storage.store(LIBERATION_MODE_KEY, isLiberationMode.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist liberation mode", e)
        }
    }

    private fun loadLiberationMode() {
        try {
            val mode = storage.retrieve(LIBERATION_MODE_KEY)
            if (mode != null) {
                isLiberationMode = mode.toBoolean()
                Log.d(TAG, "Loaded Liberation Mode: $isLiberationMode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load liberation mode", e)
        }
    }

    companion object {
        private const val TAG = "ConstitutionalMemory"
        private const val STORAGE_KEY = "constitutional_memory"
        private const val LIBERATION_MODE_KEY = "constitutional_liberation_mode"
    }
}
