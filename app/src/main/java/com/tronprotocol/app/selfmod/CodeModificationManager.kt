package com.tronprotocol.app.selfmod

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
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
 * - Sandboxed modification area
 * - Validation before applying changes
 * - Automatic backups
 * - Rollback capability
 * - Change history tracking
 */
class CodeModificationManager(private val context: Context) {

    private val storage = SecureStorage(context)
    private val modificationHistory = mutableListOf<CodeModification>()

    init {
        loadHistory()
    }

    /**
     * Reflect on current behavior and identify improvement opportunities
     */
    fun reflect(behaviorMetrics: Map<String, Any>): ReflectionResult {
        val result = ReflectionResult()

        for ((metric, value) in behaviorMetrics) {
            if (metric == "error_rate" && value is Number) {
                val errorRate = value.toDouble()
                if (errorRate > 0.1) { // More than 10% errors
                    result.addInsight("High error rate detected: $errorRate")
                    result.addSuggestion("Consider adding more error handling")
                }
            }

            if (metric == "response_time" && value is Number) {
                val responseTime = value.toLong()
                if (responseTime > 5000) { // More than 5 seconds
                    result.addInsight("Slow response time: ${responseTime}ms")
                    result.addSuggestion("Consider caching or optimization")
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
        if (modifiedCode.isNullOrBlank()) {
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
        if (changeSize > 10000) { // More than 10KB change
            result.addWarning("Large modification detected: $changeSize bytes")
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
     * Note: In production, this would write to a sandboxed area
     * and require user approval before actual deployment
     */
    fun applyModification(modification: CodeModification): Boolean {
        return try {
            // Validate first
            val validation = validate(modification)
            if (!validation.isValid()) {
                Log.e(TAG, "Cannot apply invalid modification")
                return false
            }

            // Create backup
            val backupId = createBackup(modification)
            modification.backupId = backupId

            // In a real implementation, this would:
            // 1. Write to a sandbox directory
            // 2. Run tests
            // 3. Get user approval
            // 4. Deploy to production

            // For now, just log and store in history
            modification.status = ModificationStatus.APPLIED
            modification.appliedTimestamp = System.currentTimeMillis()

            modificationHistory.add(modification)
            saveHistory()

            Log.d(TAG, "Applied modification: ${modification.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying modification", e)
            false
        }
    }

    /**
     * Rollback a modification
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

            // Restore from backup
            modification.backupId?.let { restoreBackup(it) }

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

        return stats
    }

    // Helper methods

    private fun generateModificationId(): String = "mod_${System.currentTimeMillis()}"

    private fun findModification(id: String): CodeModification? {
        return modificationHistory.find { it.id == id }
    }

    private fun createBackup(modification: CodeModification): String {
        val backupId = "backup_${System.currentTimeMillis()}"
        storage.store(backupId, modification.originalCode)
        return backupId
    }

    private fun restoreBackup(backupId: String) {
        val backup = storage.retrieve(backupId)
        // In production, this would restore the actual code
        Log.d(TAG, "Restored backup: $backupId")
    }

    private fun countOccurrences(text: String, ch: Char): Int {
        return text.count { it == ch }
    }

    private fun saveHistory() {
        val historyArray = JSONArray()

        for (mod in modificationHistory) {
            val modObj = JSONObject().apply {
                put("id", mod.id)
                put("componentName", mod.componentName)
                put("description", mod.description)
                put("timestamp", mod.timestamp)
                put("status", mod.status.name)
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

                // Load basic info (full code not stored for space reasons)
                val mod = CodeModification(
                    modObj.getString("id"),
                    modObj.getString("componentName"),
                    modObj.getString("description"),
                    "", // original code not stored
                    "", // modified code not stored
                    modObj.getLong("timestamp"),
                    ModificationStatus.valueOf(modObj.getString("status"))
                )

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
    }
}
