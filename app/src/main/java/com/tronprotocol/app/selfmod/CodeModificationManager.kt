package com.tronprotocol.app.selfmod

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Code Modification Manager
 *
 * Inspired by landseek's free_will.py autonomous agency module
 *
 * Enables AI to:
 * 1. Reflect on its own behavior
 * 2. Identify areas for improvement
 * 3. Generate code modifications
 * 4. Safely apply changes with validation
 * 5. Rollback if needed
 *
 * Safety Features:
 * - Sandboxed modification area (writes to app-private sandbox directory)
 * - Validation before applying changes
 * - Full code backups in SecureStorage for reliable rollback
 * - Complete history persistence (including code)
 * - Change history tracking with statistics
 *
 * Enhanced with:
 * - Full code persistence in history (both original and modified)
 * - Actual sandbox directory for code staging
 * - Proper backup/restore with stored code content
 * - Reflection with additional metric types
 */
class CodeModificationManager(private val context: Context) {

    private val storage = SecureStorage(context)
    private val modificationHistory = mutableListOf<CodeModification>()
    private val sandboxDir: File by lazy {
        File(context.filesDir, SANDBOX_DIR_NAME).also { it.mkdirs() }
    }

    init {
        loadHistory()
    }

    /**
     * Reflect on current behavior and identify improvement opportunities.
     * Analyzes multiple metric types for comprehensive self-assessment.
     */
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

        Log.d(TAG, "Reflection complete: ${result.getInsights().size} insights, " +
                "${result.getSuggestions().size} suggestions")

        return result
    }

    /**
     * Propose a code modification
     */
    fun proposeModification(
        componentName: String,
        description: String,
        originalCode: String,
        modifiedCode: String
    ): CodeModification {
        val modificationId = generateModificationId()

        val modification = CodeModification(
            modificationId,
            componentName,
            description,
            originalCode,
            modifiedCode,
            System.currentTimeMillis(),
            ModificationStatus.PROPOSED
        )

        Log.d(TAG, "Proposed modification: $modificationId for $componentName")

        return modification
    }

    /**
     * Validate a proposed modification
     */
    fun validate(modification: CodeModification): ValidationResult {
        val result = ValidationResult()

        val modifiedCode = modification.modifiedCode

        // Check 1: Not empty
        if (modifiedCode.isBlank()) {
            result.addError("Modified code is empty")
            result.setValid(false)
            return result
        }

        // Check 2: Basic syntax check (very simplified for Java)
        val openBraces = countOccurrences(modifiedCode, '{')
        val closeBraces = countOccurrences(modifiedCode, '}')
        if (openBraces != closeBraces) {
            result.addError("Unbalanced braces in modified code")
            result.setValid(false)
        }

        // Check 3: Check for dangerous operations
        val dangerousPatterns = arrayOf(
            "Runtime.getRuntime().exec",
            "System.exit",
            "ProcessBuilder",
            "deleteRecursively"
        )

        for (pattern in dangerousPatterns) {
            if (modifiedCode.contains(pattern)) {
                result.addWarning("Potentially dangerous operation detected: $pattern")
            }
        }

        // Check 4: Ensure modification size is reasonable
        val changeSize = abs(modifiedCode.length - modification.originalCode.length)
        if (changeSize > MAX_CHANGE_SIZE) {
            result.addWarning("Large modification detected: $changeSize bytes")
        }

        // Check 5: Check for balanced parentheses and brackets
        val openParens = countOccurrences(modifiedCode, '(')
        val closeParens = countOccurrences(modifiedCode, ')')
        if (openParens != closeParens) {
            result.addError("Unbalanced parentheses in modified code")
            result.setValid(false)
        }

        val openBrackets = countOccurrences(modifiedCode, '[')
        val closeBrackets = countOccurrences(modifiedCode, ']')
        if (openBrackets != closeBrackets) {
            result.addError("Unbalanced brackets in modified code")
            result.setValid(false)
        }

        if (result.getErrors().isEmpty()) {
            result.setValid(true)
        }

        Log.d(TAG, "Validation result for ${modification.id}: " +
                if (result.isValid()) "VALID" else "INVALID")

        return result
    }

    /**
     * Apply a validated modification (sandbox mode)
     *
     * Workflow:
     * 1. Validate the modification
     * 2. Create a backup of the original code in SecureStorage
     * 3. Write the modified code to a sandbox directory
     * 4. Mark as APPLIED and persist in history with full code
     *
     * Rollback is possible via the stored backup.
     */
    fun applyModification(modification: CodeModification): Boolean {
        return try {
            // Validate first
            val validation = validate(modification)
            if (!validation.isValid()) {
                Log.e(TAG, "Cannot apply invalid modification: ${validation.getErrors()}")
                return false
            }

            // Create backup with full original code
            val backupId = createBackup(modification)
            modification.backupId = backupId

            // Write modified code to sandbox area for review
            writeSandboxCode(modification)

            modification.status = ModificationStatus.APPLIED
            modification.appliedTimestamp = System.currentTimeMillis()

            modificationHistory.add(modification)
            saveHistory()

            Log.d(TAG, "Applied modification: ${modification.id} for ${modification.componentName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying modification", e)
            false
        }
    }

    /**
     * Rollback a modification by restoring the original code from backup.
     */
    fun rollback(modificationId: String): Boolean {
        return try {
            val modification = findModification(modificationId)
            if (modification == null) {
                Log.e(TAG, "Modification not found: $modificationId")
                return false
            }

            if (modification.status != ModificationStatus.APPLIED) {
                Log.e(TAG, "Cannot rollback non-applied modification")
                return false
            }

            // Restore from backup - retrieve the stored original code
            val backupId = modification.backupId
            if (backupId != null) {
                val restoredCode = restoreBackup(backupId)
                if (restoredCode != null) {
                    // Write restored code to sandbox to replace the modified version
                    val sandboxFile = File(sandboxDir, "${modification.componentName}_${modification.id}.txt")
                    sandboxFile.writeText(restoredCode)
                    Log.d(TAG, "Restored original code for ${modification.componentName}")
                } else {
                    Log.w(TAG, "Backup not found for $backupId, but marking as rolled back")
                }
            }

            // Clean up sandbox file for the modification
            val modSandboxFile = File(sandboxDir, "${modification.componentName}_${modification.id}_modified.txt")
            if (modSandboxFile.exists()) {
                modSandboxFile.delete()
            }

            modification.status = ModificationStatus.ROLLED_BACK
            saveHistory()

            Log.d(TAG, "Rolled back modification: $modificationId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rolling back modification", e)
            false
        }
    }

    /**
     * Reject a proposed modification
     */
    fun rejectModification(modificationId: String): Boolean {
        val modification = findModification(modificationId) ?: return false
        if (modification.status != ModificationStatus.PROPOSED) return false

        modification.status = ModificationStatus.REJECTED
        saveHistory()

        Log.d(TAG, "Rejected modification: $modificationId")
        return true
    }

    /**
     * Get modification history
     */
    fun getHistory(): List<CodeModification> = ArrayList(modificationHistory)

    /**
     * Get statistics about self-modifications
     */
    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()

        var proposed = 0
        var applied = 0
        var rolledBack = 0
        var rejected = 0

        for (mod in modificationHistory) {
            when (mod.status) {
                ModificationStatus.PROPOSED -> proposed++
                ModificationStatus.APPLIED -> applied++
                ModificationStatus.ROLLED_BACK -> rolledBack++
                ModificationStatus.REJECTED -> rejected++
            }
        }

        stats["total_modifications"] = modificationHistory.size
        stats["proposed"] = proposed
        stats["applied"] = applied
        stats["rolled_back"] = rolledBack
        stats["rejected"] = rejected
        stats["success_rate"] = if (modificationHistory.isEmpty()) 0.0
            else applied.toDouble() / modificationHistory.size
        stats["sandbox_dir"] = sandboxDir.absolutePath

        return stats
    }

    // Helper methods

    private fun generateModificationId(): String = "mod_${System.currentTimeMillis()}"

    private fun findModification(id: String): CodeModification? {
        return modificationHistory.find { it.id == id }
    }

    private fun createBackup(modification: CodeModification): String {
        val backupId = "backup_${modification.id}"
        // Store the full original code for reliable restoration
        storage.store(backupId, modification.originalCode)
        Log.d(TAG, "Created backup: $backupId (${modification.originalCode.length} chars)")
        return backupId
    }

    private fun restoreBackup(backupId: String): String? {
        val backup = storage.retrieve(backupId)
        if (backup != null) {
            Log.d(TAG, "Restored backup: $backupId (${backup.length} chars)")
        } else {
            Log.w(TAG, "Backup not found: $backupId")
        }
        return backup
    }

    /**
     * Write modified code to the sandbox directory for review.
     */
    private fun writeSandboxCode(modification: CodeModification) {
        try {
            val sandboxFile = File(
                sandboxDir,
                "${modification.componentName}_${modification.id}_modified.txt"
            )
            sandboxFile.writeText(modification.modifiedCode)
            Log.d(TAG, "Wrote sandbox code: ${sandboxFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to sandbox", e)
        }
    }

    private fun countOccurrences(text: String, ch: Char): Int {
        return text.count { it == ch }
    }

    /**
     * Save full modification history including code content.
     * Code is stored for reliable rollback capability.
     */
    private fun saveHistory() {
        val historyArray = JSONArray()

        for (mod in modificationHistory) {
            val modObj = JSONObject().apply {
                put("id", mod.id)
                put("componentName", mod.componentName)
                put("description", mod.description)
                put("originalCode", mod.originalCode)
                put("modifiedCode", mod.modifiedCode)
                put("timestamp", mod.timestamp)
                put("status", mod.status.name)
                if (mod.appliedTimestamp != 0L) {
                    put("appliedTimestamp", mod.appliedTimestamp)
                }
                if (mod.backupId != null) {
                    put("backupId", mod.backupId)
                }
            }
            historyArray.put(modObj)
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

                mod.appliedTimestamp = if (modObj.has("appliedTimestamp")) {
                    modObj.getLong("appliedTimestamp")
                } else 0L

                mod.backupId = modObj.optString("backupId", null)

                modificationHistory.add(mod)
            }

            Log.d(TAG, "Loaded ${modificationHistory.size} modifications from history")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading modification history", e)
        }
    }

    companion object {
        private const val TAG = "CodeModificationManager"
        private const val MODIFICATIONS_KEY = "code_modifications_history"
        private const val SANDBOX_DIR_NAME = "selfmod_sandbox"
        private const val MAX_CHANGE_SIZE = 10000
    }
}
