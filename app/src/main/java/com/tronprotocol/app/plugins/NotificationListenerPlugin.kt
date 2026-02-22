package com.tronprotocol.app.plugins

import android.content.Context
import org.json.JSONArray

/**
 * Plugin that exposes captured device notifications from TronNotificationListenerService.
 *
 * Commands:
 *   recent|count        – Return the N most recent notifications (default 20)
 *   search|keyword      – Search notifications by keyword in title/text
 *   count               – Return total buffered notification count
 *   clear               – Clear the notification buffer
 *   by_app|packageName  – Filter notifications by app package
 */
class NotificationListenerPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Notification Listener"
    override val description: String =
        "Read device notifications. Commands: recent|count, search|keyword, count, clear, by_app|package"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "recent" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    val items = TronNotificationListenerService.recentNotifications.take(count)
                    val arr = JSONArray()
                    items.forEach { arr.put(it) }
                    PluginResult.success("Recent $count notifications:\n${arr.toString(2)}", elapsed(start))
                }
                "search" -> {
                    val keyword = parts.getOrNull(1)?.trim()?.lowercase()
                        ?: return PluginResult.error("Usage: search|keyword", elapsed(start))
                    val matches = TronNotificationListenerService.recentNotifications.filter { n ->
                        val title = n.optString("title", "").lowercase()
                        val text = n.optString("text", "").lowercase()
                        title.contains(keyword) || text.contains(keyword)
                    }
                    val arr = JSONArray()
                    matches.forEach { arr.put(it) }
                    PluginResult.success("Found ${matches.size} matching notifications:\n${arr.toString(2)}", elapsed(start))
                }
                "count" -> {
                    PluginResult.success("Buffered notifications: ${TronNotificationListenerService.recentNotifications.size}", elapsed(start))
                }
                "clear" -> {
                    TronNotificationListenerService.recentNotifications.clear()
                    PluginResult.success("Notification buffer cleared", elapsed(start))
                }
                "by_app" -> {
                    val pkg = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: by_app|package", elapsed(start))
                    val matches = TronNotificationListenerService.recentNotifications.filter { n ->
                        n.optString("package", "") == pkg
                    }
                    val arr = JSONArray()
                    matches.forEach { arr.put(it) }
                    PluginResult.success("Found ${matches.size} from $pkg:\n${arr.toString(2)}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Notification listener error: ${e.message}", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {}
    override fun destroy() {}

    companion object {
        const val ID = "notification_listener"
    }
}
