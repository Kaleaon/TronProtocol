package com.tronprotocol.app

import android.content.Context
import android.util.Log
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

object StartupDiagnostics {
    private const val TAG = "StartupDiagnostics"
    private const val FILE_NAME = "startup_diagnostics.jsonl"
    private const val MAX_EVENTS = 60
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val displayDateFormat: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

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

    private fun enqueueWrite(context: Context, event: JSONObject) {
        ioExecutor.execute {
            try {
                val events = readEvents(context).toMutableList()
                events.add(event)

                val trimmed = if (events.size > MAX_EVENTS) {
                    events.takeLast(MAX_EVENTS)
                } else {
                    events
                }

                persistEvents(context, trimmed)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist diagnostics event", t)
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
