package com.tronprotocol.app.plugins

import android.content.Context
import org.json.JSONArray

/**
 * Plugin that exposes screen content from TronAccessibilityService.
 *
 * Commands:
 *   snapshot       – Current screen content (all visible text nodes)
 *   events|count   – Recent accessibility events
 *   status         – Accessibility service connection status
 *   find|text      – Search current screen for text
 */
class ScreenReaderPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Screen Reader"
    override val description: String =
        "Read on-screen content via accessibility. Commands: snapshot, events|count, status, find|text"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "snapshot" -> {
                    val snapshot = TronAccessibilityService.currentScreenSnapshot
                    if (snapshot == null) {
                        PluginResult.error("No screen snapshot available. Is accessibility service enabled?", elapsed(start))
                    } else {
                        PluginResult.success(snapshot.toString(2), elapsed(start))
                    }
                }
                "events" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    val events = TronAccessibilityService.recentEvents.take(count)
                    val arr = JSONArray()
                    events.forEach { arr.put(it) }
                    PluginResult.success("Recent $count events:\n${arr.toString(2)}", elapsed(start))
                }
                "status" -> {
                    val connected = TronAccessibilityService.serviceConnected
                    val eventCount = TronAccessibilityService.recentEvents.size
                    val hasSnapshot = TronAccessibilityService.currentScreenSnapshot != null
                    PluginResult.success(
                        "Accessibility service: ${if (connected) "CONNECTED" else "DISCONNECTED"}\n" +
                                "Buffered events: $eventCount\nHas screen snapshot: $hasSnapshot",
                        elapsed(start)
                    )
                }
                "find" -> {
                    val query = parts.getOrNull(1)?.trim()?.lowercase()
                        ?: return PluginResult.error("Usage: find|text", elapsed(start))
                    val snapshot = TronAccessibilityService.currentScreenSnapshot
                        ?: return PluginResult.error("No screen snapshot available", elapsed(start))
                    val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
                    val matches = JSONArray()
                    for (i in 0 until nodes.length()) {
                        val node = nodes.getJSONObject(i)
                        val text = node.optString("text", "").lowercase()
                        val desc = node.optString("description", "").lowercase()
                        if (text.contains(query) || desc.contains(query)) {
                            matches.put(node)
                        }
                    }
                    PluginResult.success("Found ${matches.length()} matches:\n${matches.toString(2)}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Screen reader error: ${e.message}", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {}
    override fun destroy() {}

    companion object {
        const val ID = "screen_reader"
    }
}
