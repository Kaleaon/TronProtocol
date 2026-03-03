package com.tronprotocol.app.telemetry

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared local telemetry schema used across core subsystems.
 */
data class TelemetryEvent(
    val operationId: String,
    val requestId: String,
    val subsystem: String,
    val latencyMs: Long,
    val status: String,
    val errorClass: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class FatalPathSnapshot(
    val operationId: String,
    val requestId: String,
    val subsystem: String,
    val errorClass: String,
    val latencyMs: Long,
    val message: String?,
    val timestampMs: Long = System.currentTimeMillis()
)

object SharedTelemetry {
    private const val TAG = "SharedTelemetry"
    private const val MAX_EVENTS = 400
    private const val MAX_FATAL_SNAPSHOTS = 25

    private val recentEvents = ConcurrentLinkedQueue<TelemetryEvent>()
    private val fatalSnapshots = ArrayDeque<FatalPathSnapshot>()
    private val requestCounter = AtomicLong(0)

    fun newRequestId(subsystem: String): String = "$subsystem-${requestCounter.incrementAndGet()}"

    fun record(event: TelemetryEvent) {
        recentEvents.add(event)
        while (recentEvents.size > MAX_EVENTS) {
            recentEvents.poll()
        }
        if (event.status == STATUS_FATAL && event.errorClass != null) {
            synchronized(fatalSnapshots) {
                fatalSnapshots.addLast(
                    FatalPathSnapshot(
                        operationId = event.operationId,
                        requestId = event.requestId,
                        subsystem = event.subsystem,
                        errorClass = event.errorClass,
                        latencyMs = event.latencyMs,
                        message = event.errorClass,
                        timestampMs = event.timestampMs
                    )
                )
                while (fatalSnapshots.size > MAX_FATAL_SNAPSHOTS) {
                    fatalSnapshots.removeFirst()
                }
            }
        }
    }

    fun recordFailureSnapshot(snapshot: FatalPathSnapshot) {
        synchronized(fatalSnapshots) {
            fatalSnapshots.addLast(snapshot)
            while (fatalSnapshots.size > MAX_FATAL_SNAPSHOTS) {
                fatalSnapshots.removeFirst()
            }
        }
    }

    inline fun <T> trace(
        subsystem: String,
        operationId: String = UUID.randomUUID().toString(),
        requestId: String = newRequestId(subsystem),
        fatalOnFailure: Boolean = false,
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            record(
                TelemetryEvent(
                    operationId = operationId,
                    requestId = requestId,
                    subsystem = subsystem,
                    latencyMs = System.currentTimeMillis() - start,
                    status = STATUS_SUCCESS
                )
            )
            result
        } catch (t: Throwable) {
            val status = if (fatalOnFailure) STATUS_FATAL else STATUS_FAILURE
            val latency = System.currentTimeMillis() - start
            record(
                TelemetryEvent(
                    operationId = operationId,
                    requestId = requestId,
                    subsystem = subsystem,
                    latencyMs = latency,
                    status = status,
                    errorClass = t.javaClass.simpleName
                )
            )
            if (status == STATUS_FATAL) {
                recordFailureSnapshot(
                    FatalPathSnapshot(
                        operationId = operationId,
                        requestId = requestId,
                        subsystem = subsystem,
                        errorClass = t.javaClass.simpleName,
                        latencyMs = latency,
                        message = t.message
                    )
                )
            }
            throw t
        }
    }

    fun exportDashboardJson(): JSONObject {
        val events = recentEvents.toList()
        val grouped = events.groupBy { it.subsystem }

        val subsystems = JSONObject()
        grouped.forEach { (subsystem, subsystemEvents) ->
            val successes = subsystemEvents.count { it.status == STATUS_SUCCESS }
            val failures = subsystemEvents.count { it.status == STATUS_FAILURE }
            val fatals = subsystemEvents.count { it.status == STATUS_FATAL }
            val avgLatency = if (subsystemEvents.isNotEmpty()) {
                subsystemEvents.map { it.latencyMs }.average().toLong()
            } else 0L
            subsystems.put(
                subsystem,
                JSONObject()
                    .put("count", subsystemEvents.size)
                    .put("avg_latency_ms", avgLatency)
                    .put("success", successes)
                    .put("failure", failures)
                    .put("fatal", fatals)
            )
        }

        val recentArray = JSONArray()
        events.takeLast(100).forEach { event ->
            recentArray.put(
                JSONObject()
                    .put("operation_id", event.operationId)
                    .put("request_id", event.requestId)
                    .put("subsystem", event.subsystem)
                    .put("latency_ms", event.latencyMs)
                    .put("status", event.status)
                    .put("error_class", event.errorClass ?: JSONObject.NULL)
                    .put("timestamp_ms", event.timestampMs)
            )
        }

        val fatalArray = JSONArray()
        synchronized(fatalSnapshots) {
            fatalSnapshots.forEach { snapshot ->
                fatalArray.put(
                    JSONObject()
                        .put("operation_id", snapshot.operationId)
                        .put("request_id", snapshot.requestId)
                        .put("subsystem", snapshot.subsystem)
                        .put("error_class", snapshot.errorClass)
                        .put("latency_ms", snapshot.latencyMs)
                        .put("message", snapshot.message ?: JSONObject.NULL)
                        .put("timestamp_ms", snapshot.timestampMs)
                )
            }
        }

        return JSONObject()
            .put("schema", "tron.telemetry.local.v1")
            .put("generated_at_ms", System.currentTimeMillis())
            .put("subsystems", subsystems)
            .put("recent_events", recentArray)
            .put("fatal_path_snapshots", fatalArray)
    }

    fun exportDashboardToFile(context: Context, fileName: String = "telemetry_dashboard_local.json"): File? {
        return try {
            val file = File(context.filesDir, fileName)
            file.writeText(exportDashboardJson().toString(2))
            file
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export dashboard", t)
            null
        }
    }

    fun resetForTests() {
        recentEvents.clear()
        synchronized(fatalSnapshots) {
            fatalSnapshots.clear()
        }
    }

    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILURE = "failure"
    const val STATUS_FATAL = "fatal"
}
