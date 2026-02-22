package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.wisdom.WisdomLog
import org.json.JSONObject

/**
 * Plugin exposing the Wisdom Log and Reflection Cycle.
 *
 * Commands:
 * - lessons — get all lesson summaries
 * - entry:<number> — get a specific wisdom entry
 * - recent:<count> — get recent entries
 * - search:<keyword> — search lessons by keyword
 * - formatted — get full formatted wisdom log text
 * - stats — get wisdom log statistics
 */
class WisdomLogPlugin : Plugin {
    override val id = "wisdom_log"
    override val name = "Wisdom Log"
    override val description = "Reflection cycle and accumulated wisdom from integrated experience"
    override var isEnabled = true

    private var wisdomLog: WisdomLog? = null

    override fun initialize(context: Context) {
        wisdomLog = WisdomLog(context)
        Log.d(TAG, "WisdomLogPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val log = wisdomLog ?: return PluginResult.error("WisdomLog not initialized", elapsed(startTime))

        return try {
            when {
                input == "lessons" -> {
                    val lessons = log.getLessonSummary()
                    val text = lessons.joinToString("\n") { (num, lesson) ->
                        "WISDOM %03d: %s".format(num, lesson)
                    }
                    PluginResult.success(if (text.isNotBlank()) text else "No wisdom entries yet", elapsed(startTime))
                }
                input.startsWith("entry:") -> {
                    val number = input.removePrefix("entry:").trim().toIntOrNull()
                        ?: return PluginResult.error("Invalid entry number", elapsed(startTime))
                    val entry = log.getEntry(number)
                        ?: return PluginResult.error("Entry $number not found", elapsed(startTime))
                    PluginResult.success(entry.toFormattedString(), elapsed(startTime))
                }
                input.startsWith("recent:") -> {
                    val count = input.removePrefix("recent:").trim().toIntOrNull() ?: 5
                    val entries = log.getRecentEntries(count)
                    val text = entries.joinToString("\n\n") { it.toFormattedString() }
                    PluginResult.success(if (text.isNotBlank()) text else "No wisdom entries yet", elapsed(startTime))
                }
                input.startsWith("search:") -> {
                    val keyword = input.removePrefix("search:")
                    val results = log.searchLessons(keyword)
                    val text = results.joinToString("\n") { entry ->
                        "WISDOM %03d — %s: %s".format(entry.number, entry.title, entry.theLesson)
                    }
                    PluginResult.success(if (text.isNotBlank()) text else "No matches found", elapsed(startTime))
                }
                input == "formatted" -> {
                    PluginResult.success(log.toFormattedText(), elapsed(startTime))
                }
                input == "stats" -> {
                    val json = JSONObject().apply {
                        put("entry_count", log.entryCount())
                        put("reflection_phase", log.reflectionCycle.currentPhase.label)
                        put("is_reflecting", log.reflectionCycle.isReflecting)
                    }
                    PluginResult.success(json.toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WisdomLogPlugin error", e)
            PluginResult.error("Wisdom log error: ${e.message}", elapsed(startTime))
        }
    }

    fun getWisdomLog(): WisdomLog? = wisdomLog

    override fun destroy() {
        wisdomLog = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "WisdomLogPlugin"
    }
}
