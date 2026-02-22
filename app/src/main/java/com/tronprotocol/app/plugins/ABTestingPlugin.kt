package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Phase 6 Self-Improvement: A/B Testing Plugin.
 *
 * Provides an A/B testing framework for comparing two approaches. Supports
 * creating tests with two variants, randomly assigning variants, recording
 * outcome scores, and concluding which variant won based on average scores.
 *
 * Commands:
 *   create|<test_name>|<variant_a>|<variant_b>  - Create a new A/B test
 *   assign|<test_name>                            - Randomly assign and return a variant
 *   record|<test_name>|<variant>|<outcome_score>  - Record an outcome score for a variant
 *   results|<test_name>                           - Show current results for a test
 *   list                                          - List all tests
 *   conclude|<test_name>                          - Conclude which variant won
 *   clear                                         - Clear all test data
 */
class ABTestingPlugin : Plugin {

    companion object {
        private const val ID = "ab_testing"
        private const val TAG = "ABTestingPlugin"
        private const val PREFS_NAME = "tronprotocol_ab_testing"
        private const val KEY_TESTS = "ab_tests"
    }

    private var prefs: SharedPreferences? = null

    // Map<testName, ABTest>
    private val tests = mutableMapOf<String, ABTest>()

    override val id: String = ID

    override val name: String = "A/B Testing"

    override val description: String =
        "A/B testing framework. Commands: create|test_name|variant_a|variant_b, " +
            "assign|test_name, record|test_name|variant|outcome_score, results|test_name, " +
            "list, conclude|test_name, clear"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "create" -> handleCreate(parts, start)
                "assign" -> handleAssign(parts, start)
                "record" -> handleRecord(parts, start)
                "results" -> handleResults(parts, start)
                "list" -> handleList(start)
                "conclude" -> handleConclude(parts, start)
                "clear" -> handleClear(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: create|test_name|variant_a|variant_b, " +
                        "assign|test_name, record|test_name|variant|outcome_score, results|test_name, " +
                        "list, conclude|test_name, clear",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ABTestingPlugin", e)
            PluginResult.error("A/B testing failed: ${e.message}", elapsed(start))
        }
    }

    private fun handleCreate(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty() || parts[3].trim().isEmpty()) {
            return PluginResult.error("Usage: create|test_name|variant_a|variant_b", elapsed(start))
        }
        val testName = parts[1].trim()
        val variantA = parts[2].trim()
        val variantB = parts[3].trim()

        if (tests.containsKey(testName)) {
            return PluginResult.error(
                "Test '$testName' already exists. Clear it first or use a different name.",
                elapsed(start)
            )
        }

        if (variantA == variantB) {
            return PluginResult.error("Variant A and Variant B must be different.", elapsed(start))
        }

        tests[testName] = ABTest(
            variantA = variantA,
            variantB = variantB,
            outcomesA = mutableListOf(),
            outcomesB = mutableListOf(),
            assignmentsA = 0,
            assignmentsB = 0,
            createdAt = System.currentTimeMillis(),
            concluded = false
        )
        saveTests()

        return PluginResult.success(
            "Created A/B test '$testName': variant A='$variantA' vs variant B='$variantB'",
            elapsed(start)
        )
    }

    private fun handleAssign(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: assign|test_name", elapsed(start))
        }
        val testName = parts[1].trim()
        val test = tests[testName]
            ?: return PluginResult.error("Test '$testName' not found.", elapsed(start))

        if (test.concluded) {
            return PluginResult.error(
                "Test '$testName' has been concluded. Create a new test for further experimentation.",
                elapsed(start)
            )
        }

        val assignVariantA = Random.nextBoolean()
        val assigned: String
        if (assignVariantA) {
            test.assignmentsA++
            assigned = test.variantA
        } else {
            test.assignmentsB++
            assigned = test.variantB
        }
        saveTests()

        return PluginResult.success(
            "Assigned variant: '$assigned' (test '$testName')",
            elapsed(start)
        )
    }

    private fun handleRecord(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty() || parts[3].trim().isEmpty()) {
            return PluginResult.error("Usage: record|test_name|variant|outcome_score", elapsed(start))
        }
        val testName = parts[1].trim()
        val variant = parts[2].trim()
        val scoreStr = parts[3].trim()

        val test = tests[testName]
            ?: return PluginResult.error("Test '$testName' not found.", elapsed(start))

        if (test.concluded) {
            return PluginResult.error("Test '$testName' has been concluded. No more data can be recorded.", elapsed(start))
        }

        val score = scoreStr.toDoubleOrNull()
            ?: return PluginResult.error("Invalid outcome score '$scoreStr'. Must be a number.", elapsed(start))

        when (variant) {
            test.variantA -> test.outcomesA.add(score)
            test.variantB -> test.outcomesB.add(score)
            else -> return PluginResult.error(
                "Unknown variant '$variant' for test '$testName'. Valid variants: '${test.variantA}', '${test.variantB}'",
                elapsed(start)
            )
        }
        saveTests()

        val outcomes = if (variant == test.variantA) test.outcomesA else test.outcomesB
        val avg = outcomes.average()

        return PluginResult.success(
            "Recorded score ${"%.2f".format(score)} for variant '$variant' in test '$testName'. " +
                "Running average: ${"%.2f".format(avg)} (${outcomes.size} observation(s))",
            elapsed(start)
        )
    }

    private fun handleResults(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: results|test_name", elapsed(start))
        }
        val testName = parts[1].trim()
        val test = tests[testName]
            ?: return PluginResult.error("Test '$testName' not found.", elapsed(start))

        return PluginResult.success(formatTestResults(testName, test), elapsed(start))
    }

    private fun handleList(start: Long): PluginResult {
        if (tests.isEmpty()) {
            return PluginResult.success("No A/B tests created.", elapsed(start))
        }

        val sb = buildString {
            append("A/B Tests (${tests.size}):\n")
            for ((name, test) in tests) {
                val status = if (test.concluded) "CONCLUDED" else "ACTIVE"
                val totalObservations = test.outcomesA.size + test.outcomesB.size
                append("  - '$name' [$status]: '${test.variantA}' vs '${test.variantB}' " +
                    "($totalObservations observation(s))\n")
            }
        }
        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleConclude(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: conclude|test_name", elapsed(start))
        }
        val testName = parts[1].trim()
        val test = tests[testName]
            ?: return PluginResult.error("Test '$testName' not found.", elapsed(start))

        if (test.concluded) {
            return PluginResult.success(
                "Test '$testName' was already concluded.\n\n${formatTestResults(testName, test)}",
                elapsed(start)
            )
        }

        if (test.outcomesA.isEmpty() && test.outcomesB.isEmpty()) {
            return PluginResult.error(
                "Cannot conclude test '$testName': no outcome data recorded for either variant.",
                elapsed(start)
            )
        }

        test.concluded = true
        saveTests()

        val avgA = if (test.outcomesA.isNotEmpty()) test.outcomesA.average() else 0.0
        val avgB = if (test.outcomesB.isNotEmpty()) test.outcomesB.average() else 0.0

        val winner: String
        val winnerAvg: Double
        val loserName: String
        val loserAvg: Double

        if (test.outcomesA.isEmpty()) {
            winner = test.variantB
            winnerAvg = avgB
            loserName = test.variantA
            loserAvg = avgA
        } else if (test.outcomesB.isEmpty()) {
            winner = test.variantA
            winnerAvg = avgA
            loserName = test.variantB
            loserAvg = avgB
        } else if (avgA >= avgB) {
            winner = test.variantA
            winnerAvg = avgA
            loserName = test.variantB
            loserAvg = avgB
        } else {
            winner = test.variantB
            winnerAvg = avgB
            loserName = test.variantA
            loserAvg = avgA
        }

        val margin = winnerAvg - loserAvg
        val confidence = when {
            test.outcomesA.size + test.outcomesB.size < 5 -> "LOW (fewer than 5 total observations)"
            test.outcomesA.size < 3 || test.outcomesB.size < 3 -> "LOW (unbalanced observations)"
            margin < 0.1 -> "LOW (very small margin)"
            margin < 1.0 -> "MEDIUM"
            else -> "HIGH"
        }

        val sb = buildString {
            append("Test '$testName' CONCLUDED:\n\n")
            append("Winner: '$winner' (avg score: ${"%.2f".format(winnerAvg)})\n")
            append("Loser:  '$loserName' (avg score: ${"%.2f".format(loserAvg)})\n")
            append("Margin: ${"%.2f".format(margin)}\n")
            append("Confidence: $confidence\n\n")
            append(formatTestResults(testName, test))
        }

        return PluginResult.success(sb.trimEnd().toString(), elapsed(start))
    }

    private fun handleClear(start: Long): PluginResult {
        val count = tests.size
        tests.clear()
        saveTests()
        return PluginResult.success("Cleared $count A/B test(s).", elapsed(start))
    }

    private fun formatTestResults(testName: String, test: ABTest): String {
        val avgA = if (test.outcomesA.isNotEmpty()) test.outcomesA.average() else null
        val avgB = if (test.outcomesB.isNotEmpty()) test.outcomesB.average() else null
        val status = if (test.concluded) "CONCLUDED" else "ACTIVE"

        return buildString {
            append("Results for '$testName' [$status]:\n")
            append("  Variant A '${test.variantA}':\n")
            append("    Assignments: ${test.assignmentsA}\n")
            append("    Observations: ${test.outcomesA.size}\n")
            if (avgA != null) {
                append("    Average score: ${"%.2f".format(avgA)}\n")
                append("    Min: ${"%.2f".format(test.outcomesA.min())}, Max: ${"%.2f".format(test.outcomesA.max())}\n")
            } else {
                append("    Average score: N/A\n")
            }
            append("  Variant B '${test.variantB}':\n")
            append("    Assignments: ${test.assignmentsB}\n")
            append("    Observations: ${test.outcomesB.size}\n")
            if (avgB != null) {
                append("    Average score: ${"%.2f".format(avgB)}\n")
                append("    Min: ${"%.2f".format(test.outcomesB.min())}, Max: ${"%.2f".format(test.outcomesB.max())}\n")
            } else {
                append("    Average score: N/A\n")
            }
            if (avgA != null && avgB != null) {
                val leader = if (avgA >= avgB) test.variantA else test.variantB
                append("  Current leader: '$leader'")
            }
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadTests()
        Log.d(TAG, "ABTestingPlugin initialized with ${tests.size} test(s)")
    }

    override fun destroy() {
        tests.clear()
        prefs = null
    }

    // ---- Persistence ----

    private fun saveTests() {
        val root = JSONObject()
        for ((name, test) in tests) {
            val obj = JSONObject()
            obj.put("variantA", test.variantA)
            obj.put("variantB", test.variantB)
            obj.put("outcomesA", JSONArray(test.outcomesA))
            obj.put("outcomesB", JSONArray(test.outcomesB))
            obj.put("assignmentsA", test.assignmentsA)
            obj.put("assignmentsB", test.assignmentsB)
            obj.put("createdAt", test.createdAt)
            obj.put("concluded", test.concluded)
            root.put(name, obj)
        }
        prefs?.edit()?.putString(KEY_TESTS, root.toString())?.apply()
    }

    private fun loadTests() {
        val data = prefs?.getString(KEY_TESTS, null) ?: return
        try {
            val root = JSONObject(data)
            tests.clear()
            for (name in root.keys()) {
                val obj = root.getJSONObject(name)
                val outcomesA = mutableListOf<Double>()
                val outcomesB = mutableListOf<Double>()
                val arrA = obj.getJSONArray("outcomesA")
                val arrB = obj.getJSONArray("outcomesB")
                for (i in 0 until arrA.length()) outcomesA.add(arrA.getDouble(i))
                for (i in 0 until arrB.length()) outcomesB.add(arrB.getDouble(i))

                tests[name] = ABTest(
                    variantA = obj.getString("variantA"),
                    variantB = obj.getString("variantB"),
                    outcomesA = outcomesA,
                    outcomesB = outcomesB,
                    assignmentsA = obj.getInt("assignmentsA"),
                    assignmentsB = obj.getInt("assignmentsB"),
                    createdAt = obj.getLong("createdAt"),
                    concluded = obj.getBoolean("concluded")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading A/B tests", e)
        }
    }

    // ---- Data class ----

    private data class ABTest(
        val variantA: String,
        val variantB: String,
        val outcomesA: MutableList<Double>,
        val outcomesB: MutableList<Double>,
        var assignmentsA: Int,
        var assignmentsB: Int,
        val createdAt: Long,
        var concluded: Boolean
    )
}
