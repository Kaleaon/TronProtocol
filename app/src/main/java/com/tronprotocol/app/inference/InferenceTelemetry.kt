package com.tronprotocol.app.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks detailed inference metrics for monitoring, optimization, and
 * adaptive routing decisions.
 *
 * Metrics tracked:
 * - Per-tier latency (p50, p95, p99)
 * - Token throughput (tokens/second per tier)
 * - Success/failure rates
 * - Quality score distribution
 * - Context utilization
 * - Routing decisions and fallback frequency
 *
 * Data is persisted to SharedPreferences for cross-session analysis.
 */
class InferenceTelemetry(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory ring buffer of recent inference events. */
    private val recentEvents = ConcurrentLinkedQueue<InferenceEvent>()

    /** Counters */
    private val totalInferences = AtomicInteger(prefs.getInt(KEY_TOTAL_INFERENCES, 0))
    private val totalTokensGenerated = AtomicLong(prefs.getLong(KEY_TOTAL_TOKENS, 0L))
    private val totalLatencyMs = AtomicLong(prefs.getLong(KEY_TOTAL_LATENCY_MS, 0L))
    private val localInferences = AtomicInteger(prefs.getInt(KEY_LOCAL_INFERENCES, 0))
    private val cloudInferences = AtomicInteger(prefs.getInt(KEY_CLOUD_INFERENCES, 0))
    private val fallbackCount = AtomicInteger(prefs.getInt(KEY_FALLBACK_COUNT, 0))
    private val errorCount = AtomicInteger(prefs.getInt(KEY_ERROR_COUNT, 0))
    private val regenerationCount = AtomicInteger(prefs.getInt(KEY_REGENERATION_COUNT, 0))

    /** Per-tier latency tracking for percentile calculations. */
    private val localLatencies = ConcurrentLinkedQueue<Long>()
    private val cloudLatencies = ConcurrentLinkedQueue<Long>()

    /** Quality score accumulator. */
    private val qualityScores = ConcurrentLinkedQueue<Float>()

    data class InferenceEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val tier: InferenceTier,
        val category: PromptTemplateEngine.QueryCategory,
        val latencyMs: Long,
        val tokenCount: Int,
        val qualityScore: Float,
        val wasFallback: Boolean,
        val wasRegenerated: Boolean,
        val success: Boolean,
        val contextTokens: Int,
        val errorMessage: String? = null
    )

    data class TelemetrySummary(
        val totalInferences: Int,
        val totalTokensGenerated: Long,
        val averageLatencyMs: Long,
        val localInferences: Int,
        val cloudInferences: Int,
        val fallbackRate: Float,
        val errorRate: Float,
        val regenerationRate: Float,
        val averageQualityScore: Float,
        val localP50LatencyMs: Long,
        val localP95LatencyMs: Long,
        val cloudP50LatencyMs: Long,
        val cloudP95LatencyMs: Long,
        val averageTokensPerSecond: Float,
        val sessionInferences: Int,
        val categoryDistribution: Map<String, Int>
    )

    /**
     * Record a completed inference event.
     */
    fun recordInference(event: InferenceEvent) {
        recentEvents.add(event)
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.poll()
        }

        totalInferences.incrementAndGet()
        if (event.success) {
            totalTokensGenerated.addAndGet(event.tokenCount.toLong())
        }
        totalLatencyMs.addAndGet(event.latencyMs)

        when (event.tier) {
            InferenceTier.LOCAL_ALWAYS_ON,
            InferenceTier.LOCAL_ON_DEMAND -> {
                localInferences.incrementAndGet()
                localLatencies.add(event.latencyMs)
                while (localLatencies.size > LATENCY_WINDOW_SIZE) localLatencies.poll()
            }
            InferenceTier.CLOUD_FALLBACK -> {
                cloudInferences.incrementAndGet()
                cloudLatencies.add(event.latencyMs)
                while (cloudLatencies.size > LATENCY_WINDOW_SIZE) cloudLatencies.poll()
            }
        }

        if (event.wasFallback) fallbackCount.incrementAndGet()
        if (!event.success) errorCount.incrementAndGet()
        if (event.wasRegenerated) regenerationCount.incrementAndGet()

        qualityScores.add(event.qualityScore)
        while (qualityScores.size > QUALITY_WINDOW_SIZE) qualityScores.poll()

        persistCounters()

        Log.d(TAG, "Recorded: tier=${event.tier.label}, latency=${event.latencyMs}ms, " +
                "tokens=${event.tokenCount}, quality=${"%.2f".format(event.qualityScore)}")
    }

    /**
     * Get a summary of all telemetry data.
     */
    fun getSummary(): TelemetrySummary {
        val total = totalInferences.get()
        val avgLatency = if (total > 0) totalLatencyMs.get() / total else 0L
        val avgQuality = if (qualityScores.isNotEmpty()) {
            qualityScores.toList().average().toFloat()
        } else 0f

        val avgTps = if (totalLatencyMs.get() > 0) {
            (totalTokensGenerated.get() * 1000f) / totalLatencyMs.get()
        } else 0f

        val categoryDist = recentEvents
            .groupingBy { it.category.name }
            .eachCount()

        return TelemetrySummary(
            totalInferences = total,
            totalTokensGenerated = totalTokensGenerated.get(),
            averageLatencyMs = avgLatency,
            localInferences = localInferences.get(),
            cloudInferences = cloudInferences.get(),
            fallbackRate = if (total > 0) fallbackCount.get().toFloat() / total else 0f,
            errorRate = if (total > 0) errorCount.get().toFloat() / total else 0f,
            regenerationRate = if (total > 0) regenerationCount.get().toFloat() / total else 0f,
            averageQualityScore = avgQuality,
            localP50LatencyMs = percentile(localLatencies.toList(), 50),
            localP95LatencyMs = percentile(localLatencies.toList(), 95),
            cloudP50LatencyMs = percentile(cloudLatencies.toList(), 50),
            cloudP95LatencyMs = percentile(cloudLatencies.toList(), 95),
            averageTokensPerSecond = avgTps,
            sessionInferences = recentEvents.size,
            categoryDistribution = categoryDist
        )
    }

    /**
     * Format a human-readable summary for display.
     */
    fun formatSummary(): String {
        val s = getSummary()
        return buildString {
            append("=== Inference Telemetry ===\n")
            append("Total: ${s.totalInferences} inferences\n")
            append("Tokens: ${s.totalTokensGenerated} generated\n")
            append("Avg latency: ${s.averageLatencyMs} ms\n")
            append("Avg quality: ${"%.2f".format(s.averageQualityScore)}\n")
            append("Throughput: ${"%.1f".format(s.averageTokensPerSecond)} tok/s\n\n")

            append("Routing:\n")
            append("  Local: ${s.localInferences} | Cloud: ${s.cloudInferences}\n")
            append("  Fallback: ${"%.1f".format(s.fallbackRate * 100)}%%\n")
            append("  Errors: ${"%.1f".format(s.errorRate * 100)}%%\n")
            append("  Regenerations: ${"%.1f".format(s.regenerationRate * 100)}%%\n\n")

            append("Latency (ms):\n")
            append("  Local  p50=${s.localP50LatencyMs} p95=${s.localP95LatencyMs}\n")
            append("  Cloud  p50=${s.cloudP50LatencyMs} p95=${s.cloudP95LatencyMs}\n")

            if (s.categoryDistribution.isNotEmpty()) {
                append("\nQuery categories:\n")
                s.categoryDistribution.entries.sortedByDescending { it.value }.forEach { (cat, count) ->
                    append("  $cat: $count\n")
                }
            }
        }
    }

    /**
     * Export telemetry as JSON for diagnostics.
     */
    fun exportJson(): JSONObject {
        val s = getSummary()
        return JSONObject().apply {
            put("total_inferences", s.totalInferences)
            put("total_tokens", s.totalTokensGenerated)
            put("avg_latency_ms", s.averageLatencyMs)
            put("avg_quality", s.averageQualityScore)
            put("avg_tps", s.averageTokensPerSecond)
            put("local_inferences", s.localInferences)
            put("cloud_inferences", s.cloudInferences)
            put("fallback_rate", s.fallbackRate)
            put("error_rate", s.errorRate)
            put("regeneration_rate", s.regenerationRate)
            put("local_p50_ms", s.localP50LatencyMs)
            put("local_p95_ms", s.localP95LatencyMs)
            put("cloud_p50_ms", s.cloudP50LatencyMs)
            put("cloud_p95_ms", s.cloudP95LatencyMs)

            val recentArray = JSONArray()
            recentEvents.takeLast(10).forEach { evt ->
                recentArray.put(JSONObject().apply {
                    put("tier", evt.tier.label)
                    put("category", evt.category.name)
                    put("latency_ms", evt.latencyMs)
                    put("tokens", evt.tokenCount)
                    put("quality", evt.qualityScore)
                    put("success", evt.success)
                })
            }
            put("recent_events", recentArray)
        }
    }

    /**
     * Reset all counters (for testing or manual reset).
     */
    fun reset() {
        recentEvents.clear()
        totalInferences.set(0)
        totalTokensGenerated.set(0)
        totalLatencyMs.set(0)
        localInferences.set(0)
        cloudInferences.set(0)
        fallbackCount.set(0)
        errorCount.set(0)
        regenerationCount.set(0)
        localLatencies.clear()
        cloudLatencies.clear()
        qualityScores.clear()
        persistCounters()
    }

    private fun persistCounters() {
        prefs.edit()
            .putInt(KEY_TOTAL_INFERENCES, totalInferences.get())
            .putLong(KEY_TOTAL_TOKENS, totalTokensGenerated.get())
            .putLong(KEY_TOTAL_LATENCY_MS, totalLatencyMs.get())
            .putInt(KEY_LOCAL_INFERENCES, localInferences.get())
            .putInt(KEY_CLOUD_INFERENCES, cloudInferences.get())
            .putInt(KEY_FALLBACK_COUNT, fallbackCount.get())
            .putInt(KEY_ERROR_COUNT, errorCount.get())
            .putInt(KEY_REGENERATION_COUNT, regenerationCount.get())
            .apply()
    }

    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((p / 100.0) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        private const val TAG = "InferenceTelemetry"
        private const val PREFS_NAME = "inference_telemetry"
        private const val MAX_RECENT_EVENTS = 100
        private const val LATENCY_WINDOW_SIZE = 50
        private const val QUALITY_WINDOW_SIZE = 50

        private const val KEY_TOTAL_INFERENCES = "total_inferences"
        private const val KEY_TOTAL_TOKENS = "total_tokens"
        private const val KEY_TOTAL_LATENCY_MS = "total_latency_ms"
        private const val KEY_LOCAL_INFERENCES = "local_inferences"
        private const val KEY_CLOUD_INFERENCES = "cloud_inferences"
        private const val KEY_FALLBACK_COUNT = "fallback_count"
        private const val KEY_ERROR_COUNT = "error_count"
        private const val KEY_REGENERATION_COUNT = "regeneration_count"
    }
}
