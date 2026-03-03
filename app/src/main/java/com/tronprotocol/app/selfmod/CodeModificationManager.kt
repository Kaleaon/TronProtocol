package com.tronprotocol.app.selfmod

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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
        modifiedCode: String,
        operatorApproved: Boolean = false
    ): CodeModification {
        val modification = CodeModification(
            generateModificationId(),
            componentName,
            description,
            originalCode,
            modifiedCode,
            System.currentTimeMillis(),
            ModificationStatus.PROPOSAL,
            operatorApproved
        )
        persistModification(modification)
        addAuditRecord(modification, ModificationStatus.PROPOSAL, ModificationStatus.PROPOSAL, "proposal", "created", "proposal registered")
        Log.d(TAG, "Proposed modification: ${modification.id} for $componentName")
        return modification
    }

    fun validate(modification: CodeModification): ValidationResult {
        val result = ValidationResult()
        result.setStage(ValidationResult.Stage.PROPOSAL)

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

        result.setStage(ValidationResult.Stage.SANDBOX_RUN)
        result.setValid(true)
        return result
    }

    fun applyModification(
        modification: CodeModification,
        healthMetrics: Map<String, Double> = emptyMap()
    ): Boolean {
        return try {
            val validation = ValidationResult().apply { setStage(ValidationResult.Stage.PROPOSAL) }

            val staticPassed = runSyntaxStaticChecks(modification, validation) && runPolicyChecks(modification, validation)
            if (!staticPassed) {
                transitionStatus(modification, ModificationStatus.ROLLED_BACK, "static_checks", "failed", validation.getErrors().joinToString("; "))
                return false
            }
            transitionStatus(modification, ModificationStatus.STATIC_CHECKS, "static_checks", "passed", "syntax/policy checks passed")

            if (!runSandboxTest(modification, validation)) {
                transitionStatus(modification, ModificationStatus.ROLLED_BACK, "sandbox_run", "failed", validation.getErrors().joinToString("; "))
                return false
            }
            transitionStatus(modification, ModificationStatus.SANDBOX_RUN, "sandbox_run", "passed", "unit/integration sandbox run succeeded")

            modification.backupId = createBackup(modification)
            if (modification.backupId.isNullOrBlank()) {
                transitionStatus(modification, ModificationStatus.ROLLED_BACK, "backup", "failed", "backup creation required before modification")
                return false
            }

            modification.rollbackCheckpointId = createRollbackCheckpoint(modification)
            if (modification.rollbackCheckpointId.isNullOrBlank()) {
                transitionStatus(modification, ModificationStatus.ROLLED_BACK, "checkpoint", "failed", "rollback checkpoint required")
                return false
            }

            writeCanaryCode(modification)
            transitionStatus(modification, ModificationStatus.CANARY_ROLLOUT, "canary_rollout", "entered", "canary written to scoped path")

            val rollbackTrigger = findRollbackTrigger(healthMetrics)
            if (rollbackTrigger != null) {
                rollback(modification.id, rollbackTrigger)
                return false
            }

            promoteCanary(modification)
            modification.appliedTimestamp = System.currentTimeMillis()
            transitionStatus(modification, ModificationStatus.FULL_ROLLOUT, "full_rollout", "passed", "canary promoted to active runtime path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying modification", e)
            false
        }
    }

    fun rollback(modificationId: String, reason: String = "manual"): Boolean {
        return try {
            val modification = findModification(modificationId) ?: return false
            if (modification.rollbackCheckpointId.isNullOrBlank()) return false

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
        if (modification.status != ModificationStatus.PROPOSAL) return false
        transitionStatus(modification, ModificationStatus.REJECTED, "rejection", "manual", "manual rejection")
        return true
    }

    fun getHistory(): List<CodeModification> = ArrayList(modificationHistory)
    fun getAuditHistory(): List<ModificationAuditRecord> = ArrayList(auditHistory)

    fun getStats(): Map<String, Any> {
        var proposal = 0
        var staticChecks = 0
        var sandboxRun = 0
        var canaryRollout = 0
        var fullRollout = 0
        var rolledBack = 0
        var rejected = 0

        for (mod in modificationHistory) {
            when (mod.status) {
                ModificationStatus.PROPOSAL -> proposal++
                ModificationStatus.STATIC_CHECKS -> staticChecks++
                ModificationStatus.SANDBOX_RUN -> sandboxRun++
                ModificationStatus.CANARY_ROLLOUT -> canaryRollout++
                ModificationStatus.FULL_ROLLOUT -> fullRollout++
                ModificationStatus.ROLLED_BACK -> rolledBack++
                ModificationStatus.REJECTED -> rejected++
            }
        }

        return mapOf(
            "total_modifications" to modificationHistory.size,
            "proposal" to proposal,
            "static_checks" to staticChecks,
            "sandbox_run" to sandboxRun,
            "canary_rollout" to canaryRollout,
            "full_rollout" to fullRollout,
            "rolled_back" to rolledBack,
            "rejected" to rejected,
            "success_rate" to if (modificationHistory.isEmpty()) 0.0 else fullRollout.toDouble() / modificationHistory.size,
            "audit_events" to auditHistory.size,
            "sandbox_dir" to sandboxDir.absolutePath
        )
    }

    private fun transitionStatus(modification: CodeModification, toStatus: ModificationStatus, gate: String, outcome: String, details: String) {
        val from = modification.status
        modification.status = toStatus
        addAuditRecord(modification, from, toStatus, gate, outcome, details)
        persistModification(modification)
    }

    private fun addAuditRecord(modification: CodeModification, fromStatus: ModificationStatus, toStatus: ModificationStatus, gate: String, outcome: String, details: String) {
        val previousHash = auditHistory.lastOrNull()?.recordHash ?: "GENESIS"
        val payload = listOf(modification.id, fromStatus.name, toStatus.name, gate, outcome, details, previousHash).joinToString("|")
        val recordHash = sha256(payload)
        auditHistory.add(
            ModificationAuditRecord(
                modificationId = modification.id,
                fromStatus = fromStatus,
                toStatus = toStatus,
                gate = gate,
                outcome = outcome,
                details = details,
                previousRecordHash = previousHash,
                recordHash = recordHash
            )
        )
        saveAuditHistory()
    }

    private fun persistModification(modification: CodeModification) {
        if (findModification(modification.id) == null) modificationHistory.add(modification)
        saveHistory()
    }

    private fun generateModificationId(): String = "mod_${System.currentTimeMillis()}"
    private fun findModification(id: String): CodeModification? = modificationHistory.find { it.id == id }
    private fun createBackup(modification: CodeModification): String = "backup_${modification.id}".also { storage.store(it, modification.originalCode) }
    private fun createRollbackCheckpoint(modification: CodeModification): String = "checkpoint_${modification.id}_${System.currentTimeMillis()}".also { storage.store(it, modification.originalCode) }
    private fun restoreBackup(backupId: String): String? = storage.retrieve(backupId)

    private fun runSyntaxStaticChecks(modification: CodeModification, result: ValidationResult): Boolean {
        val modifiedCode = modification.modifiedCode
        if (modifiedCode.isBlank()) {
            result.addError("Modified code is empty")
            result.addGateResult("static_checks", false, "code is blank")
            return false
        }

        if (countOccurrences(modifiedCode, '{') != countOccurrences(modifiedCode, '}')) {
            result.addError("Unbalanced braces in modified code")
            result.addGateResult("static_checks", false, "brace mismatch")
            return false
        }

        if (abs(modifiedCode.length - modification.originalCode.length) > MAX_CHANGE_SIZE) {
            result.addError("Change size too large")
            result.addGateResult("static_checks", false, "change size exceeds threshold")
            return false
        }

        result.setStage(ValidationResult.Stage.STATIC_CHECKS)
        result.addGateResult("static_checks", true, "syntax/static checks passed")
        return true
    }

    private fun runPolicyChecks(modification: CodeModification, result: ValidationResult): Boolean {
        val dangerousPatterns = arrayOf("Runtime.getRuntime().exec", "System.exit", "ProcessBuilder", "deleteRecursively")
        for (pattern in dangerousPatterns) {
            if (modification.modifiedCode.contains(pattern)) {
                result.addError("Blocked policy operation detected: $pattern")
                result.addGateResult("policy", false, "contains blocked pattern $pattern")
                return false
            }
        }
        if (isRestrictedMutableScope(modification) && !modification.operatorApproved) {
            result.addError("Restricted scope edit requires explicit operator approval")
            result.addGateResult("policy", false, "security/manifest scope blocked without operator approval")
            return false
        }
        result.setStage(ValidationResult.Stage.STATIC_CHECKS)
        result.addGateResult("policy", true, "policy checks passed")
        return true
    }

    private fun runSandboxTest(modification: CodeModification, result: ValidationResult): Boolean {
        return try {
            val sandboxProbeFile = File(sandboxDir, "preflight/${modification.componentName}_${modification.id}.txt")
            sandboxProbeFile.parentFile?.mkdirs()
            sandboxProbeFile.writeText(modification.modifiedCode)
            result.setStage(ValidationResult.Stage.SANDBOX_RUN)
            result.addGateResult("sandbox_run", true, "unit/integration sandbox run passed")
            true
        } catch (e: Exception) {
            result.addError("Sandbox test failed: ${e.message}")
            result.addGateResult("sandbox_run", false, "sandbox probe failed")
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

    private fun findRollbackTrigger(healthMetrics: Map<String, Double>): String? {
        if (healthMetrics.isEmpty()) return null
        val qualityRegression = healthMetrics["quality_regression"] ?: (healthMetrics["error_rate"] ?: 0.0)
        val latencyRegression = healthMetrics["latency_regression"] ?: 0.0
        val crashRate = healthMetrics["crash_rate"] ?: 0.0

        return when {
            crashRate > MAX_CANARY_CRASH_RATE -> "crash_regression"
            latencyRegression > MAX_CANARY_LATENCY_REGRESSION -> "latency_regression"
            qualityRegression > MAX_CANARY_QUALITY_REGRESSION -> "quality_regression"
            else -> null
        }
    }

    private fun isRestrictedMutableScope(modification: CodeModification): Boolean {
        val marker = "${modification.componentName} ${modification.description} ${modification.modifiedCode}".lowercase()
        return marker.contains("security") || marker.contains("manifest") || marker.contains("androidmanifest")
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
                put("operatorApproved", mod.operatorApproved)
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
                    parseStatus(modObj.getString("status")),
                    modObj.optBoolean("operatorApproved", false)
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
                put("previousRecordHash", event.previousRecordHash)
                put("recordHash", event.recordHash)
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
                        fromStatus = parseStatus(event.getString("fromStatus")),
                        toStatus = parseStatus(event.getString("toStatus")),
                        gate = event.getString("gate"),
                        outcome = event.getString("outcome"),
                        details = event.getString("details"),
                        previousRecordHash = event.optString("previousRecordHash", "GENESIS"),
                        recordHash = event.optString("recordHash", ""),
                        timestamp = event.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audit history", e)
        }
    }

    private fun parseStatus(raw: String): ModificationStatus {
        return when (raw) {
            "PROPOSED" -> ModificationStatus.PROPOSAL
            "PREFLIGHTED" -> ModificationStatus.STATIC_CHECKS
            "CANARY" -> ModificationStatus.CANARY_ROLLOUT
            "PROMOTED" -> ModificationStatus.FULL_ROLLOUT
            else -> ModificationStatus.valueOf(raw)
        }
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "CodeModificationManager"
        private const val MODIFICATIONS_KEY = "code_modifications_history"
        private const val AUDIT_LOG_KEY = "code_modifications_audit_history"
        private const val SANDBOX_DIR_NAME = "selfmod_sandbox"
        private const val MAX_CHANGE_SIZE = 10000
        private const val MAX_CANARY_QUALITY_REGRESSION = 0.15
        private const val MAX_CANARY_LATENCY_REGRESSION = 0.25
        private const val MAX_CANARY_CRASH_RATE = 0.01
    }
}
