package com.tronprotocol.app.plugins

import android.content.Context

/**
 * Simple notes plugin to demonstrate additional plugin extensibility.
 */
class NotesPlugin : Plugin {

    companion object {
        private const val ID = "notes"
    }

    private val notes = mutableListOf<String>()

    override val id: String = ID

    override val name: String = "Notes"

    override val description: String = "Create quick notes using add|text, list, and clear commands"

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
                    PluginResult.success("Added note. Total notes: ${notes.size}", System.currentTimeMillis() - start)
                }
            }
            "list" -> {
                if (notes.isEmpty()) {
                    PluginResult.success("No notes yet", System.currentTimeMillis() - start)
                } else {
                    val sb = buildString {
                        notes.forEachIndexed { index, note ->
                            append("${index + 1}. $note\n")
                        }
                    }
                    PluginResult.success(sb, System.currentTimeMillis() - start)
                }
            }
            "clear" -> {
                notes.clear()
                PluginResult.success("Cleared all notes", System.currentTimeMillis() - start)
            }
            else -> PluginResult.error("Unknown command. Use add|text, list, clear", System.currentTimeMillis() - start)
        }
    }

    override fun initialize(context: Context) {
        // No-op
    }

    override fun destroy() {
        notes.clear()
    }
}
