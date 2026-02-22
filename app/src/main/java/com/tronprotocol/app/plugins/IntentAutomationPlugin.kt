package com.tronprotocol.app.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONObject

/**
 * Fire Android Intents to control other apps without root.
 * Open URLs, compose emails, start navigation, play music, set alarms, etc.
 *
 * Commands:
 *   open_url|url                    – Open URL in browser
 *   dial|number                     – Open phone dialer
 *   navigate|latitude|longitude     – Open maps navigation
 *   share_text|text                 – Share text to other apps
 *   compose_email|to|subject|body   – Open email compose
 *   set_alarm|hour|minute|label     – Set an alarm
 *   set_timer|seconds|label         – Set a countdown timer
 *   open_app|package                – Open an app by package name
 *   open_settings                   – Open device settings
 *   search|query                    – Web search
 */
class IntentAutomationPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Intent Automation"
    override val description: String =
        "Android intent automation. Commands: open_url|url, dial|number, navigate|lat|lng, share_text|text, compose_email|to|subject|body, set_alarm|hour|min|label, set_timer|seconds|label, open_app|package, open_settings, search|query"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "open_url" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: open_url|url", elapsed(start))
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parts[1].trim()))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    PluginResult.success("Opened: ${parts[1].trim()}", elapsed(start))
                }
                "dial" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: dial|number", elapsed(start))
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${parts[1].trim()}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    PluginResult.success("Dialing: ${parts[1].trim()}", elapsed(start))
                }
                "navigate" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: navigate|lat|lng", elapsed(start))
                    val uri = Uri.parse("google.navigation:q=${parts[1].trim()},${parts[2].trim()}")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    PluginResult.success("Navigating to ${parts[1].trim()},${parts[2].trim()}", elapsed(start))
                }
                "share_text" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: share_text|text", elapsed(start))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, parts[1].trim())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    PluginResult.success("Share dialog opened", elapsed(start))
                }
                "compose_email" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: compose_email|to|subject|body", elapsed(start))
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${parts[1].trim()}")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, parts[2].trim())
                        putExtra(Intent.EXTRA_TEXT, parts[3].trim())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    PluginResult.success("Email compose opened for ${parts[1].trim()}", elapsed(start))
                }
                "set_alarm" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: set_alarm|hour|minute|label", elapsed(start))
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, parts[1].trim().toInt())
                        putExtra(AlarmClock.EXTRA_MINUTES, parts[2].trim().toInt())
                        putExtra(AlarmClock.EXTRA_MESSAGE, parts[3].trim())
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    PluginResult.success("Alarm set: ${parts[1].trim()}:${parts[2].trim()} - ${parts[3].trim()}", elapsed(start))
                }
                "set_timer" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: set_timer|seconds|label", elapsed(start))
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, parts[1].trim().toInt())
                        putExtra(AlarmClock.EXTRA_MESSAGE, parts[2].trim())
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    PluginResult.success("Timer set: ${parts[1].trim()}s - ${parts[2].trim()}", elapsed(start))
                }
                "open_app" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: open_app|package", elapsed(start))
                    val intent = ctx.packageManager.getLaunchIntentForPackage(parts[1].trim())
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        PluginResult.success("Opened: ${parts[1].trim()}", elapsed(start))
                    } else {
                        PluginResult.error("App not found: ${parts[1].trim()}", elapsed(start))
                    }
                }
                "open_settings" -> {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    PluginResult.success("Settings opened", elapsed(start))
                }
                "search" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: search|query", elapsed(start))
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra("query", parts[1].trim())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    PluginResult.success("Searching: ${parts[1].trim()}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Intent error: ${e.message}", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "intent_automation"
    }
}
