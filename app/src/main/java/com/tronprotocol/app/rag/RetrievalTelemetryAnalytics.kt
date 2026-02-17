package com.tronprotocol.app.rag

import android.util.Log
import kotlin.math.ceil

class RetrievalTelemetryAnalytics(
    private val aiId: String,
    private val sink: RetrievalTelemetrySink
) {

    fun buildSummary(limit: Int = 1000): Map<String, StrategyTelemetrySummary> {
        val events = sink.readRecent(limit).filter { it.aiId == aiId }
        if (events.isEmpty()) return emptyMap()

        return events
            .groupBy { it.strategy }
            .mapValues { (_, strategyEvents) ->
                val latencies = strategyEvents.map { it.latencyMs }.sorted()
                val emptyHits = strategyEvents.count { it.resultCount == 0 }
                val topKProxy = strategyEvents.map { it.topScore }.average().toFloat()
                val avgResults = strategyEvents.map { it.resultCount }.average().toFloat()

                StrategyTelemetrySummary(
                    sampleCount = strategyEvents.size,
                    p50LatencyMs = percentile(latencies, 0.50),
                    p95LatencyMs = percentile(latencies, 0.95),
                    emptyHitRate = emptyHits.toFloat() / strategyEvents.size,
                    topKRelevanceProxy = topKProxy,
                    avgResultCount = avgResults
                )
            }
    }

    fun buildDisplaySummary(limit: Int = 1000): String {
        val summary = buildSummary(limit)
        if (summary.isEmpty()) {
            return "No retrieval telemetry available yet."
        }

        return summary.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n") { (strategy, metrics) ->
                "$strategy: p50=${metrics.p50LatencyMs}ms, p95=${metrics.p95LatencyMs}ms, " +
                        "empty=${"%.1f".format(metrics.emptyHitRate * 100)}%, " +
                        "topKProxy=${"%.3f".format(metrics.topKRelevanceProxy)}"
            }
    }

    fun appendSummaryToStore(ragStore: RAGStore, limit: Int = 1000) {
        val summary = buildSummary(limit)
        if (summary.isEmpty()) {
            return
        }

        try {
            ragStore.addStoreMetadata("telemetry_strategy_summary", summary.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist telemetry summary", t)
        }
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ceil(sorted.size * p).toInt().coerceAtLeast(1) - 1
        return sorted[index.coerceIn(sorted.indices)]
    }

    companion object {
        private const val TAG = "RAGTelemetryAnalytics"
    }
}

data class StrategyTelemetrySummary(
    val sampleCount: Int,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val emptyHitRate: Float,
    val topKRelevanceProxy: Float,
    val avgResultCount: Float
)
