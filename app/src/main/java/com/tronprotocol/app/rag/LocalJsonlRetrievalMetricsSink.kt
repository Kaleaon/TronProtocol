package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class LocalJsonlRetrievalMetricsSink(
    context: Context,
    private val aiId: String
) : RetrievalTelemetrySink {
    private val metricsFile = File(context.filesDir, "rag_metrics_${aiId}.jsonl")

    override fun record(metric: RetrievalTelemetryEvent) {
        try {
            metricsFile.parentFile?.mkdirs()
            metricsFile.appendText(toJson(metric).toString() + "\n")
            trimIfNeeded(MAX_EVENTS)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to record telemetry event", t)
        }
    }

    override fun readRecent(limit: Int): List<RetrievalTelemetryEvent> {
        if (!metricsFile.exists()) {
            return emptyList()
        }

        return try {
            metricsFile.readLines()
                .asSequence()
                .mapNotNull { line ->
                    if (line.isBlank()) {
                        null
                    } else {
                        fromJsonSafely(line)
                    }
                }
                .toList()
                .takeLast(limit)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read telemetry events", t)
            emptyList()
        }
    }

    private fun trimIfNeeded(maxEvents: Int) {
        val lines = metricsFile.readLines()
        if (lines.size <= maxEvents) {
            return
        }

        val trimmed = lines.takeLast(maxEvents)
        metricsFile.writeText(trimmed.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun toJson(metric: RetrievalTelemetryEvent): JSONObject = JSONObject().apply {
        put("timestampMs", metric.timestampMs)
        put("aiId", metric.aiId)
        put("strategy", metric.strategy)
        put("latencyMs", metric.latencyMs)
        put("resultCount", metric.resultCount)
        put("topK", metric.topK)
        put("topScore", metric.topScore.toDouble())
        put("avgScore", metric.avgScore.toDouble())
    }

    private fun fromJsonSafely(line: String): RetrievalTelemetryEvent? {
        return try {
            val obj = JSONObject(line)
            RetrievalTelemetryEvent(
                timestampMs = obj.optLong("timestampMs", 0L),
                aiId = obj.optString("aiId", aiId),
                strategy = obj.optString("strategy", "UNKNOWN"),
                latencyMs = obj.optLong("latencyMs", 0L),
                resultCount = obj.optInt("resultCount", 0),
                topK = obj.optInt("topK", 0),
                topScore = obj.optDouble("topScore", 0.0).toFloat(),
                avgScore = obj.optDouble("avgScore", 0.0).toFloat()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Skipping malformed telemetry line", t)
            null
        }
    }

    companion object {
        private const val TAG = "RAGTelemetrySink"
        private const val MAX_EVENTS = 5000
    }
}
