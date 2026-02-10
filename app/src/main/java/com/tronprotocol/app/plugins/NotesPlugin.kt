package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Notes plugin with persistent storage.
 *
 * Supports creating, listing, searching, deleting, and clearing notes.
 * Notes are persisted across app restarts via SharedPreferences.
 *
 * Commands:
 *   add|<note text>       - Add a new note
 *   list                  - List all notes
 *   search|<query>        - Search notes containing query text
 *   delete|<number>       - Delete note by number (1-based)
 *   clear                 - Delete all notes
 *   count                 - Get the number of notes
 */
class NotesPlugin : Plugin {

    companion object {
        private const val ID = "notes"
        private const val TAG = "NotesPlugin"
        private const val PREFS_NAME = "tronprotocol_notes"
        private const val KEY_NOTES = "notes_list"
    }

    private val notes = mutableListOf<String>()
    private var prefs: SharedPreferences? = null

    override val id: String = ID

    override val name: String = "Notes"

    override val description: String =
        "Manage notes: add|text, list, search|query, delete|number, clear, count. " +
            "Notes are persisted across app restarts."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val parts = input.split("\\|".toRegex(), 2)
        val command = parts[0].trim().lowercase()

        return when (command) {
            "add" -> {
                if (parts.size < 2 || parts[1].trim().isEmpty()) {
                    PluginResult.error("Use add|<note>", System.currentTimeMillis() - start)
                } else {
                    notes.add(parts[1].trim())
                    saveNotes()
                    PluginResult.success(
                        "Added note #${notes.size}: ${parts[1].trim()}",
                        System.currentTimeMillis() - start
                    )
                }
            }
            "list" -> {
                if (notes.isEmpty()) {
                    PluginResult.success("No notes yet.", System.currentTimeMillis() - start)
                } else {
                    val sb = buildString {
                        append("Notes (${notes.size}):\n")
                        notes.forEachIndexed { index, note ->
                            append("${index + 1}. $note\n")
                        }
                    }
                    PluginResult.success(sb.trimEnd(), System.currentTimeMillis() - start)
                }
            }
            "search" -> {
                if (parts.size < 2 || parts[1].trim().isEmpty()) {
                    PluginResult.error("Use search|<query>", System.currentTimeMillis() - start)
                } else {
                    val query = parts[1].trim().lowercase()
                    val matches = notes.mapIndexedNotNull { index, note ->
                        if (note.lowercase().contains(query)) {
                            "${index + 1}. $note"
                        } else null
                    }
                    if (matches.isEmpty()) {
                        PluginResult.success(
                            "No notes matching '$query'.",
                            System.currentTimeMillis() - start
                        )
                    } else {
                        val sb = buildString {
                            append("Found ${matches.size} matching note(s):\n")
                            matches.forEach { append("$it\n") }
                        }
                        PluginResult.success(sb.trimEnd(), System.currentTimeMillis() - start)
                    }
                }
            }
            "delete" -> {
                if (parts.size < 2 || parts[1].trim().isEmpty()) {
                    PluginResult.error("Use delete|<number>", System.currentTimeMillis() - start)
                } else {
                    val index = parts[1].trim().toIntOrNull()
                    if (index == null || index < 1 || index > notes.size) {
                        PluginResult.error(
                            "Invalid note number. Use 1-${notes.size}.",
                            System.currentTimeMillis() - start
                        )
                    } else {
                        val removed = notes.removeAt(index - 1)
                        saveNotes()
                        PluginResult.success(
                            "Deleted note #$index: $removed",
                            System.currentTimeMillis() - start
                        )
                    }
                }
            }
            "clear" -> {
                val count = notes.size
                notes.clear()
                saveNotes()
                PluginResult.success(
                    "Cleared $count note(s).",
                    System.currentTimeMillis() - start
                )
            }
            "count" -> {
                PluginResult.success(
                    "${notes.size} note(s).",
                    System.currentTimeMillis() - start
                )
            }
            else -> PluginResult.error(
                "Unknown command '$command'. Use: add|text, list, search|query, delete|number, clear, count",
                System.currentTimeMillis() - start
            )
        }
    }

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadNotes()
        Log.d(TAG, "NotesPlugin initialized with ${notes.size} persisted notes")
    }

    override fun destroy() {
        notes.clear()
        prefs = null
    }

    private fun saveNotes() {
        val json = JSONArray()
        notes.forEach { json.put(it) }
        prefs?.edit()?.putString(KEY_NOTES, json.toString())?.apply()
    }

    private fun loadNotes() {
        val data = prefs?.getString(KEY_NOTES, null) ?: return
        try {
            val json = JSONArray(data)
            notes.clear()
            for (i in 0 until json.length()) {
                notes.add(json.getString(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes", e)
        }
    }
}
