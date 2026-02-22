package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.phylactery.ContinuumMemorySystem
import org.json.JSONObject

/**
 * Plugin exposing the Phylactery Continuum Memory System (CMS).
 *
 * Provides access to the four-tier memory architecture:
 * - Working Memory (volatile, per-turn)
 * - Episodic Memory (per-session summaries)
 * - Semantic Memory (consolidated knowledge)
 * - Core Identity (immutable axioms)
 *
 * Commands:
 * - store_episodic:<content> — store an episodic memory
 * - store_semantic:<category>|<content> — store semantic knowledge
 * - search:<query> — search across all memory tiers
 * - consolidate — run memory consolidation cycle
 * - close_session — close current session and summarize
 * - stats — get memory system statistics
 */
class PhylacteryPlugin : Plugin {
    override val id = "phylactery"
    override val name = "Phylactery Memory"
    override val description = "Four-tier Continuum Memory System for identity persistence"
    override var isEnabled = true

    private var cms: ContinuumMemorySystem? = null

    override fun initialize(context: Context) {
        cms = ContinuumMemorySystem(context)
        Log.d(TAG, "PhylacteryPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val memory = cms ?: return PluginResult.error("Phylactery not initialized", elapsed(startTime))

        return try {
            when {
                input.startsWith("store_episodic:") -> {
                    val content = input.removePrefix("store_episodic:")
                    val entry = memory.addEpisodicMemory(content)
                    PluginResult.success("Stored episodic memory: ${entry.id}", elapsed(startTime))
                }
                input.startsWith("store_semantic:") -> {
                    val parts = input.removePrefix("store_semantic:").split("|", limit = 2)
                    if (parts.size < 2) return PluginResult.error("Format: store_semantic:category|content", elapsed(startTime))
                    val entry = memory.addSemanticKnowledge(parts[1], parts[0])
                    PluginResult.success("Stored semantic knowledge: ${entry.id}", elapsed(startTime))
                }
                input.startsWith("search:") -> {
                    val query = input.removePrefix("search:")
                    val results = memory.searchAllMemory(query, topK = 5)
                    val json = JSONObject().apply {
                        put("count", results.size)
                        put("results", results.map { it.toJson().toString() }.toString())
                    }
                    PluginResult.success(json.toString(), elapsed(startTime))
                }
                input == "consolidate" -> {
                    memory.runConsolidation()
                    PluginResult.success("Memory consolidation complete", elapsed(startTime))
                }
                input == "close_session" -> {
                    memory.closeSession()
                    PluginResult.success("Session closed, new session: ${memory.currentSessionId}", elapsed(startTime))
                }
                input == "stats" -> {
                    PluginResult.success(JSONObject(memory.getStats()).toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "PhylacteryPlugin error", e)
            PluginResult.error("Phylactery error: ${e.message}", elapsed(startTime))
        }
    }

    /** Direct access to the CMS for other system components. */
    fun getMemorySystem(): ContinuumMemorySystem? = cms

    override fun destroy() {
        cms = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "PhylacteryPlugin"
    }
}
