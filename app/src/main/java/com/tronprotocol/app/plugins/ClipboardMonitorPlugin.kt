package com.tronprotocol.app.plugins

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Monitors clipboard changes and exposes clipboard history.
 *
 * Commands:
 *   current    – Current clipboard content
 *   history    – Recent clipboard entries
 *   start      – Begin monitoring clipboard changes
 *   stop       – Stop monitoring
 *   clear      – Clear clipboard history buffer
 */
class ClipboardMonitorPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Clipboard Monitor"
    override val description: String =
        "Monitor clipboard. Commands: current, history, start, stop, clear"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null
    private var clipboardManager: ClipboardManager? = null
    private var monitoring = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClip()
    }

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val command = input.split("\\|".toRegex())[0].trim().lowercase()

            when (command) {
                "current" -> {
                    val clip = clipboardManager?.primaryClip
                    if (clip == null || clip.itemCount == 0) {
                        PluginResult.success("Clipboard is empty", elapsed(start))
                    } else {
                        val text = clip.getItemAt(0).text?.toString() ?: clip.getItemAt(0).uri?.toString() ?: ""
                        val desc = clip.description?.label?.toString() ?: ""
                        PluginResult.success("Clipboard ($desc): ${text.take(2000)}", elapsed(start))
                    }
                }
                "history" -> {
                    val arr = JSONArray()
                    clipboardHistory.forEach { arr.put(it) }
                    PluginResult.success("Clipboard history (${arr.length()} entries):\n${arr.toString(2)}", elapsed(start))
                }
                "start" -> {
                    if (!monitoring) {
                        clipboardManager?.addPrimaryClipChangedListener(listener)
                        monitoring = true
                    }
                    PluginResult.success("Clipboard monitoring started", elapsed(start))
                }
                "stop" -> {
                    if (monitoring) {
                        clipboardManager?.removePrimaryClipChangedListener(listener)
                        monitoring = false
                    }
                    PluginResult.success("Clipboard monitoring stopped", elapsed(start))
                }
                "clear" -> {
                    clipboardHistory.clear()
                    PluginResult.success("Clipboard history cleared", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Clipboard error: ${e.message}", elapsed(start))
        }
    }

    private fun captureClip() {
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString() ?: clip.getItemAt(0).uri?.toString() ?: return
            if (text.isBlank()) return

            val entry = JSONObject().apply {
                put("text", text.take(1000))
                put("timestamp", System.currentTimeMillis())
                put("label", clip.description?.label?.toString() ?: "")
            }
            clipboardHistory.addFirst(entry)
            while (clipboardHistory.size > MAX_HISTORY) {
                clipboardHistory.removeLast()
            }
        } catch (_: Exception) {}
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun destroy() {
        if (monitoring) {
            clipboardManager?.removePrimaryClipChangedListener(listener)
            monitoring = false
        }
        clipboardManager = null
        appContext = null
    }

    companion object {
        const val ID = "clipboard_monitor"
        private const val MAX_HISTORY = 50
        val clipboardHistory = ConcurrentLinkedDeque<JSONObject>()
    }
}
