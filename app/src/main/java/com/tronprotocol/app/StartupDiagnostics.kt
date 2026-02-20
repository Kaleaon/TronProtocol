package com.tronprotocol.app

import android.content.Context
import android.util.Log
import com.tronprotocol.app.rag.LocalJsonlRetrievalMetricsSink
import com.tronprotocol.app.rag.RetrievalTelemetryAnalytics
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object StartupDiagnostics {
    private const val TAG = "StartupDiagnostics"
    private const val FILE_NAME = "startup_diagnostics.jsonl"
    private const val MAX_EVENTS = 60
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val displayDateFormat: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val debugSections = ConcurrentHashMap<String, String>()
    private val eventCount = AtomicInteger(-1)

    @JvmStatic
    fun recordMilestone(context: Context, milestone: String, details: String = "") {
        enqueueWrite(context.applicationContext, buildEvent("milestone", milestone, details, null))
    }

    @JvmStatic
    fun recordError(context: Context, milestone: String, throwable: Throwable) {
        enqueueWrite(
            context.applicationContext,
            buildEvent(
                type = "error",
                milestone = milestone,
                details = throwable.message ?: "No throwable message",
                stackTrace = stackTraceToString(throwable)
            )
        )
    }

    @JvmStatic
    fun getEventsForDisplay(context: Context, limit: Int = 20): String {
        val events = readEvents(context.applicationContext)
            .takeLast(limit)
            .reversed()

        if (events.isEmpty()) {
            return "No startup diagnostics captured yet."
        }

        return events.joinToString(separator = "\n\n") { event ->
            val type = event.optString("type", "unknown").uppercase(Locale.US)
            val timestamp = event.optString("timestamp", "unknown-time")
            val milestone = event.optString("milestone", "unknown-milestone")
            val details = event.optString("details", "")
            val stackTrace = event.optString("stackTrace", "")

            buildString {
                append("[")
                append(type)
                append("] ")
                append(timestamp)
                append(" · ")
                append(milestone)
                if (details.isNotBlank()) {
                    append("\n")
                    append(details)
                }
                if (stackTrace.isNotBlank()) {
                    append("\n")
                    append(stackTrace)
                }
            }
        }
    }

    @JvmStatic
    fun getRetrievalDiagnosticsSummary(context: Context, aiId: String, limit: Int = 200): String {
        val sink = LocalJsonlRetrievalMetricsSink(context.applicationContext, aiId)
        val analytics = RetrievalTelemetryAnalytics(aiId, sink)
        return analytics.buildDisplaySummary(limit)
    }

    fun setDebugSection(sectionName: String, value: String) {
        if (sectionName.isBlank()) return
        debugSections[sectionName] = value
    }

    @JvmStatic
    fun exportDebugLog(context: Context): File {
        val events = readEvents(context.applicationContext)
        val timestamp = Instant.now().toEpochMilli()
        val outFile = File(context.cacheDir, "tron_debug_log_$timestamp.txt")

        outFile.bufferedWriter().use { writer ->
            writer.appendLine("Tron Protocol Debug Log")
            writer.appendLine("Generated: ${displayDateFormat.format(Instant.now())}")
            writer.appendLine("Events captured: ${events.size}")
            writer.appendLine()

            if (debugSections.isNotEmpty()) {
                writer.appendLine("=== Runtime Sections ===")
                debugSections.toSortedMap().forEach { (name, value) ->
                    writer.appendLine("[$name]")
                    writer.appendLine(value)
                    writer.appendLine()
                }
            }

            writer.appendLine("=== Startup Diagnostics ===")
            events.forEach { event ->
                writer.appendLine(event.toString())
            }
        }

        return outFile
    }

    private fun enqueueWrite(context: Context, event: JSONObject) {
        ioExecutor.execute {
            try {
                val file = diagnosticsFile(context)
                if (eventCount.get() == -1) {
                    val count = if (file.exists()) {
                        var c = 0
                        file.forEachLine { if (it.isNotBlank()) c++ }
                        c
                    } else {
                        0
                    }
                    eventCount.set(count)
                }

                file.appendText(event.toString() + "\n")
                val currentCount = eventCount.incrementAndGet()

                if (currentCount >= MAX_EVENTS * 2) {
                    val events = readEvents(context)
                    if (events.size > MAX_EVENTS) {
                        val trimmed = events.takeLast(MAX_EVENTS)
                        persistEvents(context, trimmed)
                        eventCount.set(trimmed.size)
                    } else {
                        eventCount.set(events.size)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist diagnostics event", t)
                eventCount.set(-1)
            }
        }
    }

    private fun buildEvent(
        type: String,
        milestone: String,
        details: String,
        stackTrace: String?
    ): JSONObject {
        val now = Instant.now()
        return JSONObject().apply {
            put("type", type)
            put("milestone", milestone)
            put("details", details)
            put("timestampEpochMs", now.toEpochMilli())
            put("timestamp", displayDateFormat.format(now))
            if (!stackTrace.isNullOrBlank()) {
                put("stackTrace", stackTrace)
            }
        }
    }

    private fun diagnosticsFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private fun readEvents(context: Context): List<JSONObject> {
        val file = diagnosticsFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        return try {
            file.readLines()
                .mapNotNull { line ->
                    if (line.isBlank()) {
                        null
                    } else {
                        try {
                            JSONObject(line)
                        } catch (jsonError: Throwable) {
                            Log.w(TAG, "Skipping malformed diagnostics entry", jsonError)
                            null
                        }
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed reading diagnostics file", t)
            emptyList()
        }
    }

    private fun persistEvents(context: Context, events: List<JSONObject>) {
        val file = diagnosticsFile(context)
        val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tempFile.bufferedWriter().use { writer ->
                events.forEach { event ->
                    writer.write(event.toString())
                    writer.newLine()
                }
            }

            if (!tempFile.renameTo(file)) {
                file.bufferedWriter().use { writer ->
                    events.forEach { event ->
                        writer.write(event.toString())
                        writer.newLine()
                    }
                }
                tempFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing diagnostics file", t)
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        return StringWriter().use { sw ->
            PrintWriter(sw).use { pw ->
                throwable.printStackTrace(pw)
                sw.toString()
            }
        }
    }
}
