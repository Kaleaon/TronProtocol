package com.tronprotocol.app.rag

interface RetrievalTelemetrySink {
    fun record(metric: RetrievalTelemetryEvent)
    fun readRecent(limit: Int): List<RetrievalTelemetryEvent>
}

data class RetrievalTelemetryEvent(
    val timestampMs: Long,
    val aiId: String,
    val strategy: String,
    val latencyMs: Long,
    val resultCount: Int,
    val topK: Int,
    val topScore: Float,
    val avgScore: Float
)
