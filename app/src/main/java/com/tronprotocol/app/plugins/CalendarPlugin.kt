package com.tronprotocol.app.plugins

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read device calendar events for schedule awareness.
 *
 * Commands:
 *   today              – Events today
 *   upcoming|days      – Events in next N days (default 7)
 *   search|keyword     – Search events by title
 *   calendars          – List available calendars
 *   free_slots|date    – Find free time slots on a date (yyyy-MM-dd)
 */
class CalendarPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Calendar"
    override val description: String =
        "Read device calendar. Commands: today, upcoming|days, search|keyword, calendars, free_slots|date"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "today" -> {
                    val now = System.currentTimeMillis()
                    val endOfDay = now + (24 * 60 * 60 * 1000) - (now % (24 * 60 * 60 * 1000))
                    val startOfDay = endOfDay - (24 * 60 * 60 * 1000)
                    val events = queryEvents(ctx, startOfDay, endOfDay)
                    PluginResult.success("Today's events (${events.length()}):\n${events.toString(2)}", elapsed(start))
                }
                "upcoming" -> {
                    val days = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 7
                    val now = System.currentTimeMillis()
                    val end = now + (days.toLong() * 24 * 60 * 60 * 1000)
                    val events = queryEvents(ctx, now, end)
                    PluginResult.success("Next $days days (${events.length()} events):\n${events.toString(2)}", elapsed(start))
                }
                "search" -> {
                    val keyword = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: search|keyword", elapsed(start))
                    val events = searchEvents(ctx, keyword)
                    PluginResult.success("Found ${events.length()} events matching '$keyword':\n${events.toString(2)}", elapsed(start))
                }
                "calendars" -> {
                    val calendars = listCalendars(ctx)
                    PluginResult.success("Available calendars:\n${calendars.toString(2)}", elapsed(start))
                }
                "free_slots" -> {
                    val dateStr = parts.getOrNull(1)?.trim() ?: run {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        sdf.format(Date())
                    }
                    val slots = findFreeSlots(ctx, dateStr)
                    PluginResult.success("Free slots on $dateStr:\n${slots.toString(2)}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("Calendar permission not granted", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("Calendar error: ${e.message}", elapsed(start))
        }
    }

    private fun queryEvents(ctx: Context, startMs: Long, endMs: Long): JSONArray {
        val events = JSONArray()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )
            cursor?.let {
                while (it.moveToNext()) {
                    events.put(JSONObject().apply {
                        put("id", it.getLong(0))
                        put("title", it.getString(1) ?: "")
                        put("start", dateFormat.format(Date(it.getLong(2))))
                        put("end", if (!it.isNull(3)) dateFormat.format(Date(it.getLong(3))) else "")
                        put("location", it.getString(4) ?: "")
                        put("description", (it.getString(5) ?: "").take(200))
                        put("allDay", it.getInt(6) == 1)
                    })
                }
            }
        } finally {
            cursor?.close()
        }
        return events
    }

    private fun searchEvents(ctx: Context, keyword: String): JSONArray {
        val now = System.currentTimeMillis()
        val futureEnd = now + (90L * 24 * 60 * 60 * 1000) // 90 days
        val all = queryEvents(ctx, now - (30L * 24 * 60 * 60 * 1000), futureEnd)
        val matches = JSONArray()
        for (i in 0 until all.length()) {
            val event = all.getJSONObject(i)
            val title = event.optString("title", "").lowercase()
            val desc = event.optString("description", "").lowercase()
            val loc = event.optString("location", "").lowercase()
            if (title.contains(keyword.lowercase()) || desc.contains(keyword.lowercase()) || loc.contains(keyword.lowercase())) {
                matches.put(event)
            }
        }
        return matches
    }

    private fun listCalendars(ctx: Context): JSONArray {
        val cals = JSONArray()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    cals.put(JSONObject().apply {
                        put("id", it.getLong(0))
                        put("name", it.getString(1) ?: "")
                        put("account", it.getString(2) ?: "")
                        put("visible", it.getInt(3) == 1)
                    })
                }
            }
        } finally {
            cursor?.close()
        }
        return cals
    }

    private fun findFreeSlots(ctx: Context, dateStr: String): JSONArray {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayStart = sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
        val dayEnd = dayStart + (24 * 60 * 60 * 1000)

        val events = queryEvents(ctx, dayStart, dayEnd)
        val busySlots = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.optBoolean("allDay")) continue
            val startStr = e.optString("start")
            val endStr = e.optString("end")
            try {
                val s = dateFormat.parse(startStr)?.time ?: continue
                val en = if (endStr.isNotEmpty()) dateFormat.parse(endStr)?.time ?: (s + 3600000) else s + 3600000
                busySlots.add(Pair(s, en))
            } catch (_: Exception) {}
        }

        busySlots.sortBy { it.first }

        // Working hours: 8 AM to 10 PM
        val workStart = dayStart + (8 * 60 * 60 * 1000)
        val workEnd = dayStart + (22 * 60 * 60 * 1000)

        val freeSlots = JSONArray()
        var cursor = workStart
        for ((busyStart, busyEnd) in busySlots) {
            if (busyStart > cursor && busyStart <= workEnd) {
                freeSlots.put(JSONObject().apply {
                    put("start", dateFormat.format(Date(cursor)))
                    put("end", dateFormat.format(Date(busyStart)))
                    put("duration_min", (busyStart - cursor) / 60000)
                })
            }
            if (busyEnd > cursor) cursor = busyEnd
        }
        if (cursor < workEnd) {
            freeSlots.put(JSONObject().apply {
                put("start", dateFormat.format(Date(cursor)))
                put("end", dateFormat.format(Date(workEnd)))
                put("duration_min", (workEnd - cursor) / 60000)
            })
        }
        return freeSlots
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "calendar"
    }
}
