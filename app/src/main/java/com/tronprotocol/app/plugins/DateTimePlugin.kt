package com.tronprotocol.app.plugins

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * DateTime Plugin.
 *
 * Provides date/time operations and timezone conversions.
 * Inspired by ToolNeuron's DateTimePlugin and landseek's tools.
 */
class DateTimePlugin : Plugin {

    companion object {
        private const val TAG = "DateTimePlugin"
        private const val ID = "datetime"
    }

    private var context: Context? = null

    override val id: String = ID

    override val name: String = "Date & Time"

    override val description: String =
        "Get current date/time, convert timezones, calculate date differences. " +
            "Commands: 'now', 'now UTC', 'add 5 days', 'diff 2024-01-01'"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val trimmed = input.trim().lowercase()

            val result = when {
                trimmed.startsWith("now") -> getCurrentTime(trimmed)
                trimmed.startsWith("add") || trimmed.startsWith("subtract") -> calculateDate(trimmed)
                trimmed.startsWith("diff") -> calculateDifference(trimmed)
                trimmed.startsWith("format") -> formatDate(trimmed)
                else -> getCurrentTime("now")
            }

            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(result, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("DateTime operation failed: ${e.message}", duration)
        }
    }

    /**
     * Get current time in specified timezone.
     */
    private fun getCurrentTime(command: String): String {
        val parts = command.split("\\s+".toRegex())
        val timezone = if (parts.size > 1) parts[1].uppercase() else "default"

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        if (timezone != "default") {
            sdf.timeZone = TimeZone.getTimeZone(timezone)
        }

        return sdf.format(Date())
    }

    /**
     * Calculate future/past date.
     * Format: "add 5 days" or "subtract 3 weeks"
     */
    private fun calculateDate(command: String): String {
        val parts = command.split("\\s+".toRegex())
        if (parts.size < 3) {
            throw Exception("Invalid format. Use: add/subtract number unit")
        }

        val isAdd = parts[0] == "add"
        var amount = parts[1].toInt()
        val unit = parts[2]

        if (!isAdd) {
            amount = -amount
        }

        val cal = Calendar.getInstance()

        when (unit) {
            "second", "seconds" -> cal.add(Calendar.SECOND, amount)
            "minute", "minutes" -> cal.add(Calendar.MINUTE, amount)
            "hour", "hours" -> cal.add(Calendar.HOUR, amount)
            "day", "days" -> cal.add(Calendar.DAY_OF_MONTH, amount)
            "week", "weeks" -> cal.add(Calendar.WEEK_OF_YEAR, amount)
            "month", "months" -> cal.add(Calendar.MONTH, amount)
            "year", "years" -> cal.add(Calendar.YEAR, amount)
            else -> throw Exception("Unknown unit: $unit")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(cal.time)
    }

    /**
     * Calculate difference between now and a date.
     * Format: "diff 2024-01-01"
     */
    private fun calculateDifference(command: String): String {
        val parts = command.split("\\s+".toRegex())
        if (parts.size < 2) {
            throw Exception("Invalid format. Use: diff YYYY-MM-DD")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val targetDate = requireNotNull(sdf.parse(parts[1])) { "Invalid date: ${parts[1]}" }
        val now = Date()

        val diffMs = targetDate.time - now.time
        var diffDays = diffMs / (1000 * 60 * 60 * 24)
        var diffHours = (diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)

        val direction = if (diffMs >= 0) "from now" else "ago"
        diffDays = abs(diffDays)
        diffHours = abs(diffHours)

        return "$diffDays days and $diffHours hours $direction"
    }

    /**
     * Format current date with custom format.
     * Format: "format FORMAT_STRING"
     */
    private fun formatDate(command: String): String {
        val parts = command.split("\\s+".toRegex(), 2)
        if (parts.size < 2) {
            throw Exception("Invalid format. Use: format FORMAT_STRING")
        }

        val sdf = SimpleDateFormat(parts[1], Locale.getDefault())
        return sdf.format(Date())
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
