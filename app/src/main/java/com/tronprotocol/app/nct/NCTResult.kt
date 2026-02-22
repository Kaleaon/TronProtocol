package com.tronprotocol.app.nct

import org.json.JSONObject

/**
 * Result of a single NCT axis test.
 */
data class NCTResult(
    val axis: NCTAxis,
    val score: Float,
    val passed: Boolean,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("axis", axis.label)
        put("score", score.toDouble())
        put("passed", passed)
        put("details", details)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): NCTResult = NCTResult(
            axis = NCTAxis.entries.find { it.label == json.getString("axis") }
                ?: NCTAxis.SITUATED_MEMORY,
            score = json.getDouble("score").toFloat(),
            passed = json.getBoolean("passed"),
            details = json.getString("details"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

/**
 * Complete NCT test run result across all five axes.
 */
data class NCTTestRun(
    val results: Map<NCTAxis, NCTResult>,
    val overallScore: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    val allPassed: Boolean get() = results.values.all { it.passed }

    fun toJson(): JSONObject = JSONObject().apply {
        put("overall_score", overallScore.toDouble())
        put("all_passed", allPassed)
        put("timestamp", timestamp)
        val axisResults = JSONObject()
        results.forEach { (axis, result) ->
            axisResults.put(axis.label, result.toJson())
        }
        put("axis_results", axisResults)
    }
}
