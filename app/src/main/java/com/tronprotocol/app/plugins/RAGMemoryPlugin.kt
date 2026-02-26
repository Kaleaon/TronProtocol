package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.rag.MemoryConsolidationManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy

/**
 * RAG Memory Plugin — Exposes the RAG memory system through the plugin interface.
 *
 * Commands:
 *   stats                      → Return chunk count, graph stats, strategy list
 *   consolidate                → Trigger sleep-like memory consolidation
 *   consolidation_status       → Report last consolidation result
 *   store|<content>            → Store a new memory chunk
 *   retrieve|<query>           → Retrieve top-K relevant memories (hybrid strategy)
 *   retrieve|<query>|<strategy>→ Retrieve with a specific strategy
 */
class RAGMemoryPlugin : Plugin {
    override val id: String = "rag_memory"
    override val name: String = "RAG Memory"
    override val description: String = "Retrieval-Augmented Generation memory store with MemRL-inspired Q-value learning"
    override var isEnabled: Boolean = true

    private var ragStore: RAGStore? = null
    private var consolidationManager: MemoryConsolidationManager? = null
    private var lastConsolidationMessage: String? = null

    override fun initialize(context: Context) {
        try {
            ragStore = RAGStore(context, AI_ID)
            consolidationManager = MemoryConsolidationManager(context)
            Log.i(TAG, "RAGMemoryPlugin initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RAG store", e)
        }
    }

    override fun execute(input: String): PluginResult {
        val store = ragStore
            ?: return PluginResult.error("RAG store not initialized")

        val parts = input.split("|", limit = 3)
        val command = parts[0].trim().lowercase()

        return when (command) {
            "stats" -> getStats(store)
            "consolidate" -> runConsolidation(store)
            "consolidation_status" -> getConsolidationStatus()
            "store" -> {
                val content = parts.getOrNull(1)?.trim()
                    ?: return PluginResult.error("Usage: store|<content>")
                storeMemory(store, content)
            }
            "retrieve" -> {
                val query = parts.getOrNull(1)?.trim()
                    ?: return PluginResult.error("Usage: retrieve|<query>[|<strategy>]")
                val strategyName = parts.getOrNull(2)?.trim()?.uppercase()
                retrieveMemories(store, query, strategyName)
            }
            else -> PluginResult.error("Unknown command: $command. Use: stats, consolidate, consolidation_status, store, retrieve")
        }
    }

    private fun getStats(store: RAGStore): PluginResult {
        return try {
            val graphStats = store.knowledgeGraph.getStats()
            val entityCount = graphStats["entity_count"] ?: 0
            val chunkCount = graphStats["chunk_count"] ?: 0
            val totalEdges = graphStats["total_edges"] ?: 0
            val strategies = RetrievalStrategy.entries.joinToString(", ") { it.name.lowercase() }

            val text = buildString {
                append("=== RAG Memory Stats ===\n")
                append("Graph entities: $entityCount\n")
                append("Graph chunks: $chunkCount\n")
                append("Graph edges: $totalEdges\n")
                append("Learning rate: ${store.learningRate}\n")
                append("Strategies: $strategies")
            }
            PluginResult.success(text)
        } catch (e: Exception) {
            PluginResult.error("Failed to gather stats: ${e.message}")
        }
    }

    private fun runConsolidation(store: RAGStore): PluginResult {
        val manager = consolidationManager
            ?: return PluginResult.error("Consolidation manager not initialized")
        return try {
            val result = manager.consolidate(store)
            lastConsolidationMessage = buildString {
                append("Consolidation complete.\n")
                append("Strengthened: ${result.strengthened}, Weakened: ${result.weakened}\n")
                append("Forgotten: ${result.forgotten}, Connections: ${result.connections}\n")
                append("Duration: ${result.duration} ms")
            }
            PluginResult.success(lastConsolidationMessage!!)
        } catch (e: Exception) {
            val msg = "Consolidation failed: ${e.message}"
            lastConsolidationMessage = msg
            PluginResult.error(msg)
        }
    }

    private fun getConsolidationStatus(): PluginResult {
        val msg = lastConsolidationMessage ?: "No consolidation has been run this session."
        return PluginResult.success(msg)
    }

    private fun storeMemory(store: RAGStore, content: String): PluginResult {
        return try {
            store.addMemory(content, DEFAULT_IMPORTANCE)
            PluginResult.success("Memory stored (${content.length} chars)")
        } catch (e: Exception) {
            PluginResult.error("Store failed: ${e.message}")
        }
    }

    private fun retrieveMemories(store: RAGStore, query: String, strategyName: String?): PluginResult {
        return try {
            val strategy = if (strategyName != null) {
                try {
                    RetrievalStrategy.valueOf(strategyName)
                } catch (_: IllegalArgumentException) {
                    return PluginResult.error("Unknown strategy: $strategyName. Available: ${RetrievalStrategy.entries.joinToString()}")
                }
            } else {
                RetrievalStrategy.HYBRID
            }

            val results = store.retrieve(query, strategy, TOP_K)
            if (results.isEmpty()) {
                return PluginResult.success("No memories found for query: $query")
            }

            val text = buildString {
                append("Found ${results.size} result(s) using ${strategy.name}:\n\n")
                results.forEachIndexed { i, r ->
                    append("[${i + 1}] score=%.3f\n".format(r.score))
                    append("    ${r.chunk.content.take(200)}\n\n")
                }
            }
            PluginResult.success(text)
        } catch (e: Exception) {
            PluginResult.error("Retrieval failed: ${e.message}")
        }
    }

    override fun destroy() {
        ragStore = null
        consolidationManager = null
    }

    companion object {
        private const val TAG = "RAGMemoryPlugin"
        private const val AI_ID = "tronprotocol_ai"
        private const val TOP_K = 5
        private const val DEFAULT_IMPORTANCE = 0.5f
    }
}
