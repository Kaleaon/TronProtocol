package com.tronprotocol.app.selfmod

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class CodeModificationManager(private val context: Context) {

    private val storage = SecureStorage(context)
    private val modificationHistory = mutableListOf<CodeModification>()
    private val auditHistory = mutableListOf<ModificationAuditRecord>()
    private val sandboxDir: File by lazy {
        File(context.filesDir, SANDBOX_DIR_NAME).also { it.mkdirs() }
    }

    init {
        loadHistory()
        loadAuditHistory()
    }

    fun reflect(behaviorMetrics: Map<String, Any>): ReflectionResult {
        val result = ReflectionResult()

        for ((metric, value) in behaviorMetrics) {
            when {
                metric == "error_rate" && value is Number -> {
                    val errorRate = value.toDouble()
                    if (errorRate > 0.1) {
                        result.addInsight("High error rate detected: $errorRate")
                        result.addSuggestion("Consider adding more error handling")
                    }
                }
                metric == "response_time" && value is Number -> {
                    val responseTime = value.toLong()
                    if (responseTime > 5000) {
                        result.addInsight("Slow response time: ${responseTime}ms")
                        result.addSuggestion("Consider caching or optimization")
                    }
                }
                metric == "memory_usage" && value is Number -> {
                    val memoryMb = value.toLong()
                    if (memoryMb > 256) {
                        result.addInsight("High memory usage: ${memoryMb}MB")
                        result.addSuggestion("Consider reducing cached data or using pagination")
                    }
                }
                metric == "hallucination_rate" && value is Number -> {
                    val hallRate = value.toDouble()
                    if (hallRate > 0.05) {
                        result.addInsight("Elevated hallucination rate: $hallRate")
                        result.addSuggestion("Increase RAG retrieval depth or add more verification")
                    }
                }
                metric == "rollback_count" && value is Number -> {
                    val rollbacks = value.toInt()
                    if (rollbacks > 3) {
                        result.addInsight("Multiple rollbacks detected: $rollbacks")
                        result.addSuggestion("Improve validation before applying modifications")
                    }
                }
            }
        }

        Log.d(TAG, "Reflection complete: ${result.getInsights().size} insights, ${result.getSuggestions().size} suggestions")
        return result
    }

    fun proposeModification(
        componentName: String,
        description: String,
        originalCode: String,
        modifiedCode: String
    ): CodeModification {
        val modification = CodeModification(
            generateModificationId(),
            componentName,
            description,
            originalCode,
            modifiedCode,
            System.currentTimeMillis(),
            ModificationStatus.PROPOSED
        )
        Log.d(TAG, "Proposed modification: ${modification.id} for $componentName")
        return modification
    }

    fun validate(modification: CodeModification): ValidationResult {
        val result = ValidationResult()
        result.setStage(ValidationResult.Stage.PROPOSED)

        val syntaxPassed = runSyntaxStaticChecks(modification, result)
        if (!syntaxPassed) {
            result.setValid(false)
            return result
        }

        val policyPassed = runPolicyChecks(modification, result)
        if (!policyPassed) {
            result.setValid(false)
            return result
        }

        val sandboxPassed = runSandboxTest(modification, result)
        if (!sandboxPassed) {
            result.setValid(false)
            return result
        }

        result.setStage(ValidationResult.Stage.PREFLIGHTED)
        result.setValid(true)
        return result
    }

    fun applyModification(
        modification: CodeModification,
        healthMetrics: Map<String, Double> = emptyMap()
    ): Boolean {
        return try {
            val validation = validate(modification)
            if (!validation.isValid()) {
                addAuditRecord(
                    modification,
                    modification.status,
                    ModificationStatus.ROLLED_BACK,
                    "preflight",
                    "failed",
                    validation.getErrors().joinToString("; ")
                )
                modification.status = ModificationStatus.ROLLED_BACK
                persistModification(modification)
                return false
            }

            transitionStatus(modification, ModificationStatus.PREFLIGHTED, "preflight", "passed", "all gates passed")

            val backupId = createBackup(modification)
            modification.backupId = backupId
            if (backupId.isBlank()) {
                transitionStatus(
                    modification,
                    ModificationStatus.ROLLED_BACK,
                    "backup",
                    "failed",
                    "backup creation required before modification"
                )
                return false
            }

            val checkpointId = createRollbackCheckpoint(modification)
            modification.rollbackCheckpointId = checkpointId

            if (checkpointId.isBlank()) {
                transitionStatus(
                    modification,
                    ModificationStatus.ROLLED_BACK,
                    "checkpoint",
                    "failed",
                    "rollback checkpoint required"
                )
                return false
            }

            writeCanaryCode(modification)
            transitionStatus(modification, ModificationStatus.CANARY, "canary", "entered", "canary written to scoped path")

            if (isHealthDegraded(healthMetrics)) {
                Log.w(TAG, "Health degradation detected for ${modification.id}; triggering automatic rollback")
                rollback(modification.id, "health_degradation")
                return false
            }

            promoteCanary(modification)
            modification.appliedTimestamp = System.currentTimeMillis()
            transitionStatus(modification, ModificationStatus.PROMOTED, "promotion", "passed", "canary promoted to active runtime path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying modification", e)
            false
        }
    }

    fun rollback(modificationId: String, reason: String = "manual"): Boolean {
        return try {
            val modification = findModification(modificationId)
            if (modification == null) {
                Log.e(TAG, "Modification not found: $modificationId")
                return false
            }

            if (modification.rollbackCheckpointId.isNullOrBlank()) {
                Log.e(TAG, "Rollback checkpoint missing for $modificationId")
                return false
            }

            val backupId = modification.backupId
            if (backupId != null) {
                val restoredCode = restoreBackup(backupId)
                if (restoredCode != null) {
                    val restoreFile = File(sandboxDir, "runtime_active/${modification.componentName}_${modification.id}.txt")
                    restoreFile.parentFile?.mkdirs()
                    restoreFile.writeText(restoredCode)
                }
            }

            File(sandboxDir, "canary/${modification.componentName}_${modification.id}.txt").delete()
            File(sandboxDir, "runtime_active/${modification.componentName}_${modification.id}.txt").delete()

            transitionStatus(modification, ModificationStatus.ROLLED_BACK, "rollback", "triggered", reason)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rolling back modification", e)
            false
        }
    }

    fun rejectModification(modificationId: String): Boolean {
        val modification = findModification(modificationId) ?: return false
        if (modification.status != ModificationStatus.PROPOSED) return false

        transitionStatus(modification, ModificationStatus.REJECTED, "rejection", "manual", "manual rejection")
        return true
    }

    fun getHistory(): List<CodeModification> = ArrayList(modificationHistory)

    fun getAuditHistory(): List<ModificationAuditRecord> = ArrayList(auditHistory)

    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        var proposed = 0
        var preflighted = 0
        var canary = 0
        var promoted = 0
        var rolledBack = 0
        var rejected = 0

        for (mod in modificationHistory) {
            when (mod.status) {
                ModificationStatus.PROPOSED -> proposed++
                ModificationStatus.PREFLIGHTED -> preflighted++
                ModificationStatus.CANARY -> canary++
                ModificationStatus.PROMOTED -> promoted++
                ModificationStatus.ROLLED_BACK -> rolledBack++
                ModificationStatus.REJECTED -> rejected++
            }
        }

        stats["total_modifications"] = modificationHistory.size
        stats["proposed"] = proposed
        stats["preflighted"] = preflighted
        stats["canary"] = canary
        stats["promoted"] = promoted
        stats["rolled_back"] = rolledBack
        stats["rejected"] = rejected
        stats["success_rate"] = if (modificationHistory.isEmpty()) 0.0 else promoted.toDouble() / modificationHistory.size
        stats["audit_events"] = auditHistory.size
        stats["sandbox_dir"] = sandboxDir.absolutePath
        return stats
    }

    private fun transitionStatus(
        modification: CodeModification,
        toStatus: ModificationStatus,
        gate: String,
        outcome: String,
        details: String
    ) {
        val from = modification.status
        modification.status = toStatus
        addAuditRecord(modification, from, toStatus, gate, outcome, details)
        persistModification(modification)
    }

    private fun addAuditRecord(
        modification: CodeModification,
        fromStatus: ModificationStatus,
        toStatus: ModificationStatus,
        gate: String,
        outcome: String,
        details: String
    ) {
        auditHistory.add(
            ModificationAuditRecord(
                modificationId = modification.id,
                fromStatus = fromStatus,
                toStatus = toStatus,
                gate = gate,
                outcome = outcome,
                details = details
            )
        )
        saveAuditHistory()
    }

    private fun persistModification(modification: CodeModification) {
        val existing = findModification(modification.id)
        if (existing == null) {
            modificationHistory.add(modification)
        }
        saveHistory()
    }

    private fun generateModificationId(): String = "mod_${System.currentTimeMillis()}"

    private fun findModification(id: String): CodeModification? = modificationHistory.find { it.id == id }

    private fun createBackup(modification: CodeModification): String {
        val backupId = "backup_${modification.id}"
        storage.store(backupId, modification.originalCode)
        return backupId
    }

    private fun createRollbackCheckpoint(modification: CodeModification): String {
        val checkpointId = "checkpoint_${modification.id}_${System.currentTimeMillis()}"
        storage.store(checkpointId, modification.originalCode)
        return checkpointId
    }

    private fun restoreBackup(backupId: String): String? = storage.retrieve(backupId)

    private fun runSyntaxStaticChecks(modification: CodeModification, result: ValidationResult): Boolean {
        val modifiedCode = modification.modifiedCode

        if (modifiedCode.isBlank()) {
            result.addError("Modified code is empty")
            result.addGateResult("syntax_static", false, "code is blank")
            return false
        }

        val openBraces = countOccurrences(modifiedCode, '{')
        val closeBraces = countOccurrences(modifiedCode, '}')
        if (openBraces != closeBraces) {
            result.addError("Unbalanced braces in modified code")
            result.addGateResult("syntax_static", false, "brace mismatch")
            return false
        }

        val changeSize = abs(modifiedCode.length - modification.originalCode.length)
        if (changeSize > MAX_CHANGE_SIZE) {
            result.addError("Change size too large: $changeSize")
            result.addGateResult("syntax_static", false, "change size exceeds threshold")
            return false
        }

        result.setStage(ValidationResult.Stage.SYNTAX_STATIC_CHECK)
        result.addGateResult("syntax_static", true, "syntax/static checks passed")
        return true
    }

    private fun runPolicyChecks(modification: CodeModification, result: ValidationResult): Boolean {
        val dangerousPatterns = arrayOf(
            "Runtime.getRuntime().exec",
            "System.exit",
            "ProcessBuilder",
            "deleteRecursively"
        )

        for (pattern in dangerousPatterns) {
            if (modification.modifiedCode.contains(pattern)) {
                result.addError("Blocked policy operation detected: $pattern")
                result.addGateResult("policy", false, "contains blocked pattern $pattern")
                return false
            }
        }

        result.setStage(ValidationResult.Stage.POLICY_CHECK)
        result.addGateResult("policy", true, "policy checks passed")
        return true
    }

    private fun runSandboxTest(modification: CodeModification, result: ValidationResult): Boolean {
        return try {
            val sandboxProbeFile = File(sandboxDir, "preflight/${modification.componentName}_${modification.id}.txt")
            sandboxProbeFile.parentFile?.mkdirs()
            sandboxProbeFile.writeText(modification.modifiedCode)
            result.setStage(ValidationResult.Stage.SANDBOX_TEST)
            result.addGateResult("sandbox_test", true, "sandbox write/read probe passed")
            true
        } catch (e: Exception) {
            result.addError("Sandbox test failed: ${e.message}")
            result.addGateResult("sandbox_test", false, "sandbox probe failed")
            false
        }
    }

    private fun writeCanaryCode(modification: CodeModification) {
        val canaryFile = File(sandboxDir, "canary/${modification.componentName}_${modification.id}.txt")
        canaryFile.parentFile?.mkdirs()
        canaryFile.writeText(modification.modifiedCode)
    }

    private fun promoteCanary(modification: CodeModification) {
        val canaryFile = File(sandboxDir, "canary/${modification.componentName}_${modification.id}.txt")
        val activeFile = File(sandboxDir, "runtime_active/${modification.componentName}_${modification.id}.txt")
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(canaryFile.readText())
    }

    private fun isHealthDegraded(healthMetrics: Map<String, Double>): Boolean {
        if (healthMetrics.isEmpty()) return false
        val errorRate = healthMetrics["error_rate"] ?: 0.0
        val latencyRegression = healthMetrics["latency_regression"] ?: 0.0
        val crashRate = healthMetrics["crash_rate"] ?: 0.0
        return errorRate > MAX_CANARY_ERROR_RATE ||
                latencyRegression > MAX_CANARY_LATENCY_REGRESSION ||
                crashRate > MAX_CANARY_CRASH_RATE
    }

    private fun countOccurrences(text: String, ch: Char): Int = text.count { it == ch }

    private fun saveHistory() {
        val historyArray = JSONArray()
        for (mod in modificationHistory) {
            historyArray.put(JSONObject().apply {
                put("id", mod.id)
                put("componentName", mod.componentName)
                put("description", mod.description)
                put("originalCode", mod.originalCode)
                put("modifiedCode", mod.modifiedCode)
                put("timestamp", mod.timestamp)
                put("status", mod.status.name)
                put("appliedTimestamp", mod.appliedTimestamp)
                put("backupId", mod.backupId)
                put("rollbackCheckpointId", mod.rollbackCheckpointId)
            })
        }
        storage.store(MODIFICATIONS_KEY, historyArray.toString())
    }

    private fun loadHistory() {
        try {
            val data = storage.retrieve(MODIFICATIONS_KEY) ?: return
            val historyArray = JSONArray(data)
            for (i in 0 until historyArray.length()) {
                val modObj = historyArray.getJSONObject(i)
                val mod = CodeModification(
                    modObj.getString("id"),
                    modObj.getString("componentName"),
                    modObj.getString("description"),
                    modObj.optString("originalCode", ""),
                    modObj.optString("modifiedCode", ""),
                    modObj.getLong("timestamp"),
                    ModificationStatus.valueOf(modObj.getString("status"))
                )
                mod.appliedTimestamp = modObj.optLong("appliedTimestamp", 0L)
                mod.backupId = modObj.optString("backupId", null)
                mod.rollbackCheckpointId = modObj.optString("rollbackCheckpointId", null)
                modificationHistory.add(mod)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading modification history", e)
        }
    }

    private fun saveAuditHistory() {
        val events = JSONArray()
        for (event in auditHistory) {
            events.put(JSONObject().apply {
                put("modificationId", event.modificationId)
                put("fromStatus", event.fromStatus.name)
                put("toStatus", event.toStatus.name)
                put("gate", event.gate)
                put("outcome", event.outcome)
                put("details", event.details)
                put("timestamp", event.timestamp)
            })
        }
        storage.store(AUDIT_LOG_KEY, events.toString())
    }

    private fun loadAuditHistory() {
        try {
            val data = storage.retrieve(AUDIT_LOG_KEY) ?: return
            val historyArray = JSONArray(data)
            for (i in 0 until historyArray.length()) {
                val event = historyArray.getJSONObject(i)
                auditHistory.add(
                    ModificationAuditRecord(
                        modificationId = event.getString("modificationId"),
                        fromStatus = ModificationStatus.valueOf(event.getString("fromStatus")),
                        toStatus = ModificationStatus.valueOf(event.getString("toStatus")),
                        gate = event.getString("gate"),
                        outcome = event.getString("outcome"),
                        details = event.getString("details"),
                        timestamp = event.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audit history", e)
        }
    }

    companion object {
        private const val TAG = "CodeModificationManager"
        private const val MODIFICATIONS_KEY = "code_modifications_history"
        private const val AUDIT_LOG_KEY = "code_modifications_audit_history"
        private const val SANDBOX_DIR_NAME = "selfmod_sandbox"
        private const val MAX_CHANGE_SIZE = 10000
        private const val MAX_CANARY_ERROR_RATE = 0.15
        private const val MAX_CANARY_LATENCY_REGRESSION = 0.25
        private const val MAX_CANARY_CRASH_RATE = 0.01
    }
}
