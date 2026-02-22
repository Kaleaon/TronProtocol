package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class BenchmarkSuitePlugin : Plugin {
    override val id: String = "benchmark_suite"
    override val name: String = "Benchmark Suite"
    override val description: String = "Self-testing battery that runs plugin calls and validates results against expected patterns"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var initialized = false

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("benchmark_suite_plugin", Context.MODE_PRIVATE)
        initialized = true
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        if (!initialized) {
            return PluginResult.error("Plugin not initialized", System.currentTimeMillis() - startTime)
        }

        val parts = input.split("|").map { it.trim() }
        if (parts.isEmpty()) {
            return PluginResult.error("No command provided. Available: run_all, run, results, add_test, list_tests", System.currentTimeMillis() - startTime)
        }

        return try {
            when (parts[0].lowercase()) {
                "run_all" -> handleRunAll(startTime)
                "run" -> handleRun(parts, startTime)
                "results" -> handleResults(startTime)
                "add_test" -> handleAddTest(parts, startTime)
                "list_tests" -> handleListTests(startTime)
                else -> PluginResult.error("Unknown command: ${parts[0]}. Available: run_all, run, results, add_test, list_tests", System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            PluginResult.error("Error executing command: ${e.message}", System.currentTimeMillis() - startTime)
        }
    }

    private fun handleRunAll(startTime: Long): PluginResult {
        val tests = loadTests()
        if (tests.length() == 0) {
            return PluginResult.success("No tests defined. Use add_test to create tests.", System.currentTimeMillis() - startTime)
        }

        val runResults = JSONArray()
        var passed = 0
        var failed = 0

        for (i in 0 until tests.length()) {
            val test = tests.getJSONObject(i)
            val result = executeTest(test)
            runResults.put(result)
            if (result.optBoolean("passed", false)) passed++ else failed++
        }

        val runRecord = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("total_tests", tests.length())
            put("passed", passed)
            put("failed", failed)
            put("pass_rate", if (tests.length() > 0) String.format("%.1f%%", passed.toDouble() / tests.length() * 100) else "N/A")
            put("details", runResults)
        }
        storeRunResult(runRecord)

        val summary = JSONObject().apply {
            put("total", tests.length())
            put("passed", passed)
            put("failed", failed)
            put("pass_rate", runRecord.getString("pass_rate"))
            put("results", runResults)
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(summary.toString(2), elapsed)
    }

    private fun handleRun(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: run|test_name", System.currentTimeMillis() - startTime)
        }
        val testName = parts[1]
        val tests = loadTests()
        var targetTest: JSONObject? = null

        for (i in 0 until tests.length()) {
            val test = tests.getJSONObject(i)
            if (test.getString("name") == testName) {
                targetTest = test
                break
            }
        }

        if (targetTest == null) {
            return PluginResult.error("Test not found: $testName", System.currentTimeMillis() - startTime)
        }

        val result = executeTest(targetTest)
        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(result.toString(2), elapsed)
    }

    private fun handleResults(startTime: Long): PluginResult {
        val resultsArray = loadRunResults()
        val total = resultsArray.length()

        val summary = JSONObject().apply {
            put("total_runs", total)
            if (total > 0) {
                val latest = resultsArray.getJSONObject(total - 1)
                put("latest_run", JSONObject().apply {
                    put("timestamp", latest.optLong("timestamp"))
                    put("total_tests", latest.optInt("total_tests"))
                    put("passed", latest.optInt("passed"))
                    put("failed", latest.optInt("failed"))
                    put("pass_rate", latest.optString("pass_rate"))
                })

                // Trend over last 5 runs
                val trendArray = JSONArray()
                val trendStart = maxOf(0, total - 5)
                for (i in trendStart until total) {
                    val run = resultsArray.getJSONObject(i)
                    trendArray.put(JSONObject().apply {
                        put("timestamp", run.optLong("timestamp"))
                        put("pass_rate", run.optString("pass_rate"))
                    })
                }
                put("trend", trendArray)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(summary.toString(2), elapsed)
    }

    private fun handleAddTest(parts: List<String>, startTime: Long): PluginResult {
        if (parts.size < 5) {
            return PluginResult.error("Usage: add_test|name|plugin_id|input|expected_pattern", System.currentTimeMillis() - startTime)
        }
        val testName = parts[1]
        val pluginId = parts[2]
        val testInput = parts[3]
        val expectedPattern = parts[4]

        val tests = loadTests()

        // Check for duplicate name
        for (i in 0 until tests.length()) {
            if (tests.getJSONObject(i).getString("name") == testName) {
                return PluginResult.error("Test with name '$testName' already exists", System.currentTimeMillis() - startTime)
            }
        }

        val test = JSONObject().apply {
            put("name", testName)
            put("plugin_id", pluginId)
            put("input", testInput)
            put("expected_pattern", expectedPattern)
            put("created_at", System.currentTimeMillis())
        }
        tests.put(test)
        prefs.edit().putString("test_definitions", tests.toString()).apply()

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success("Test '$testName' added: plugin=$pluginId, input='$testInput', expected='$expectedPattern'", elapsed)
    }

    private fun handleListTests(startTime: Long): PluginResult {
        val tests = loadTests()
        val listing = JSONObject().apply {
            put("total_tests", tests.length())
            val testList = JSONArray()
            for (i in 0 until tests.length()) {
                val test = tests.getJSONObject(i)
                testList.put(JSONObject().apply {
                    put("name", test.getString("name"))
                    put("plugin_id", test.getString("plugin_id"))
                    put("input", test.getString("input"))
                    put("expected_pattern", test.getString("expected_pattern"))
                })
            }
            put("tests", testList)
        }

        val elapsed = System.currentTimeMillis() - startTime
        return PluginResult.success(listing.toString(2), elapsed)
    }

    private fun executeTest(test: JSONObject): JSONObject {
        val testName = test.getString("name")
        val pluginId = test.getString("plugin_id")
        val testInput = test.getString("input")
        val expectedPattern = test.getString("expected_pattern")

        val result = JSONObject().apply {
            put("name", testName)
            put("plugin_id", pluginId)
            put("input", testInput)
            put("expected_pattern", expectedPattern)
            put("timestamp", System.currentTimeMillis())
        }

        return try {
            val pluginManager = PluginManager.getInstance(appContext)
            val plugin = pluginManager.getPlugin(pluginId)

            if (plugin == null) {
                result.apply {
                    put("passed", false)
                    put("error", "Plugin not found: $pluginId")
                    put("actual_output", "")
                }
            } else if (!plugin.isEnabled) {
                result.apply {
                    put("passed", false)
                    put("error", "Plugin is disabled: $pluginId")
                    put("actual_output", "")
                }
            } else {
                val execResult = plugin.execute(testInput)
                val outputData = execResult.data ?: ""
                val matches = outputData.contains(expectedPattern, ignoreCase = true) ||
                    try {
                        Regex(expectedPattern, RegexOption.IGNORE_CASE).containsMatchIn(outputData)
                    } catch (e: Exception) {
                        false
                    }

                result.apply {
                    put("passed", matches && execResult.success)
                    put("actual_output", outputData.take(500))
                    put("execution_time_ms", execResult.executionTimeMs)
                    put("plugin_success", execResult.success)
                    put("pattern_match", matches)
                }
            }
        } catch (e: Exception) {
            result.apply {
                put("passed", false)
                put("error", "Exception during execution: ${e.message}")
                put("actual_output", "")
            }
        }
    }

    private fun loadTests(): JSONArray {
        val raw = prefs.getString("test_definitions", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun loadRunResults(): JSONArray {
        val raw = prefs.getString("run_results", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun storeRunResult(result: JSONObject) {
        val results = loadRunResults()
        results.put(result)
        // Keep at most 50 run results
        val trimmed = if (results.length() > 50) {
            val newArray = JSONArray()
            for (i in results.length() - 50 until results.length()) {
                newArray.put(results.getJSONObject(i))
            }
            newArray
        } else {
            results
        }
        prefs.edit().putString("run_results", trimmed.toString()).apply()
    }

    override fun destroy() {
        initialized = false
    }
}
