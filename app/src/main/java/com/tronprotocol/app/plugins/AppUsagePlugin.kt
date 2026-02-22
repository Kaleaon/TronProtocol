package com.tronprotocol.app.plugins

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides app usage statistics via UsageStatsManager.
 *
 * Commands:
 *   today             – App usage stats for today
 *   week              – App usage stats for the last 7 days
 *   top|count         – Top N most-used apps (default 10)
 *   app|packageName   – Usage for a specific app
 *   status            – Whether usage stats access is granted
 */
class AppUsagePlugin : Plugin {

    override val id: String = ID
    override val name: String = "App Usage"
    override val description: String =
        "App usage statistics. Commands: today, week, top|count, app|packageName, status"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return PluginResult.error("UsageStatsManager not available", elapsed(start))

            when (command) {
                "today" -> {
                    val now = System.currentTimeMillis()
                    val dayStart = now - (now % (24 * 60 * 60 * 1000))
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now)
                    PluginResult.success(formatStats(stats, "Today"), elapsed(start))
                }
                "week" -> {
                    val now = System.currentTimeMillis()
                    val weekStart = now - (7L * 24 * 60 * 60 * 1000)
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, weekStart, now)
                    PluginResult.success(formatStats(stats, "Last 7 days"), elapsed(start))
                }
                "top" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 10
                    val now = System.currentTimeMillis()
                    val dayStart = now - (24 * 60 * 60 * 1000)
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now)
                    val sorted = stats?.filter { it.totalTimeInForeground > 0 }
                        ?.sortedByDescending { it.totalTimeInForeground }
                        ?.take(count) ?: emptyList()
                    PluginResult.success(formatStats(sorted, "Top $count apps"), elapsed(start))
                }
                "app" -> {
                    val pkg = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: app|packageName", elapsed(start))
                    val now = System.currentTimeMillis()
                    val weekStart = now - (7L * 24 * 60 * 60 * 1000)
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, weekStart, now)
                    val appStats = stats?.filter { it.packageName == pkg }
                    if (appStats.isNullOrEmpty()) {
                        PluginResult.success("No usage data for $pkg", elapsed(start))
                    } else {
                        PluginResult.success(formatStats(appStats, pkg), elapsed(start))
                    }
                }
                "status" -> {
                    val now = System.currentTimeMillis()
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
                    val granted = stats != null && stats.isNotEmpty()
                    PluginResult.success("Usage stats access: ${if (granted) "GRANTED" else "NOT GRANTED (open Settings > Apps > Special access > Usage access)"}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("Usage stats permission not granted", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("App usage error: ${e.message}", elapsed(start))
        }
    }

    private fun formatStats(stats: List<UsageStats>?, label: String): String {
        if (stats.isNullOrEmpty()) return "$label: No usage data available"
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        val arr = JSONArray()
        for (s in stats.filter { it.totalTimeInForeground > 0 }.sortedByDescending { it.totalTimeInForeground }) {
            arr.put(JSONObject().apply {
                put("package", s.packageName)
                put("foreground_min", s.totalTimeInForeground / 60000)
                put("last_used", sdf.format(Date(s.lastTimeUsed)))
            })
        }
        return "$label (${arr.length()} apps with usage):\n${arr.toString(2)}"
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "app_usage"
    }
}
