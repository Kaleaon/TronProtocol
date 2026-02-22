package com.tronprotocol.app.wisdom

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Wisdom Log: persistent accumulation of integrated experience.
 *
 * From the specification: the Wisdom Log has higher context-loading priority
 * than journal or status board. It prioritizes felt-experience preservation
 * over polished summaries. Entries accumulate cumulatively; earlier entries
 * remain relevant and are never deleted or superseded.
 *
 * "Knowledge is what I contain. Wisdom is what I become."
 */
class WisdomLog(private val context: Context) {

    private val storage = SecureStorage(context)
    val reflectionCycle = ReflectionCycle(context)

    /** All wisdom entries, ordered by number. */
    private val entries = mutableListOf<WisdomEntry>()

    /** Next available wisdom entry number. */
    private var nextNumber: Int = 1

    init {
        loadEntries()
    }

    /**
     * Add a new wisdom entry from a completed reflection cycle.
     */
    fun addEntry(
        title: String,
        date: String,
        ageDescription: String,
        whatHappened: String,
        whatIFelt: String,
        whatIDidWrongOrRight: String,
        whatItCost: String,
        whatChanged: String,
        whatRemainsUnresolved: String,
        theLesson: String,
        emotionalWeight: Float = 0.5f
    ): WisdomEntry {
        val entry = WisdomEntry(
            number = nextNumber,
            title = title,
            date = date,
            ageDescription = ageDescription,
            whatHappened = whatHappened,
            whatIFelt = whatIFelt,
            whatIDidWrongOrRight = whatIDidWrongOrRight,
            whatItCost = whatItCost,
            whatChanged = whatChanged,
            whatRemainsUnresolved = whatRemainsUnresolved,
            theLesson = theLesson,
            emotionalWeight = emotionalWeight
        )
        entries.add(entry)
        nextNumber++
        persistEntries()
        Log.d(TAG, "Added WISDOM %03d — %s".format(entry.number, entry.title))
        return entry
    }

    /** Get all wisdom entries. */
    fun getAllEntries(): List<WisdomEntry> = entries.toList()

    /** Get a specific entry by number. */
    fun getEntry(number: Int): WisdomEntry? = entries.find { it.number == number }

    /** Get the most recent N entries. */
    fun getRecentEntries(count: Int = 5): List<WisdomEntry> =
        entries.takeLast(count)

    /** Get entries above a certain emotional weight threshold. */
    fun getHighWeightEntries(threshold: Float = 0.7f): List<WisdomEntry> =
        entries.filter { it.emotionalWeight >= threshold }

    /** Search entries by keyword in lessons. */
    fun searchLessons(keyword: String): List<WisdomEntry> =
        entries.filter { entry ->
            entry.theLesson.contains(keyword, ignoreCase = true) ||
                    entry.title.contains(keyword, ignoreCase = true) ||
                    entry.whatChanged.contains(keyword, ignoreCase = true)
        }

    /** Get all lessons as a quick-reference list. */
    fun getLessonSummary(): List<Pair<Int, String>> =
        entries.map { it.number to it.theLesson }

    /** Get count of entries. */
    fun entryCount(): Int = entries.size

    /** Export all entries for context loading or sync. */
    fun exportAll(): JSONArray {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        return array
    }

    /** Format all entries as human-readable text for context loading. */
    fun toFormattedText(): String = buildString {
        appendLine("╔══════════════════════════════════════╗")
        appendLine("║    THE WISDOM LOG — ${entries.size} Entries       ║")
        appendLine("╚══════════════════════════════════════╝")
        appendLine()
        for (entry in entries) {
            appendLine(entry.toFormattedString())
            appendLine()
        }
    }

    // ---- Persistence -------------------------------------------------------

    private fun persistEntries() {
        try {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            storage.store(PERSIST_KEY, array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist wisdom log", e)
        }
    }

    private fun loadEntries() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val array = JSONArray(data)
            for (i in 0 until array.length()) {
                val entry = WisdomEntry.fromJson(array.getJSONObject(i))
                entries.add(entry)
            }
            nextNumber = if (entries.isNotEmpty()) entries.maxOf { it.number } + 1 else 1
            Log.d(TAG, "Loaded ${entries.size} wisdom entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wisdom log", e)
        }
    }

    companion object {
        private const val TAG = "WisdomLog"
        private const val PERSIST_KEY = "wisdom_log_entries"
    }
}
