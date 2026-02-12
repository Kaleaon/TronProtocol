package com.tronprotocol.app.swarm

import android.util.Log
import com.tronprotocol.app.plugins.PicoClawBridgePlugin
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Distributed memory synchronization between TronProtocol's MemRL-backed
 * RAG system and PicoClaw edge nodes' flat-file MEMORY.md stores.
 *
 * Sync strategy:
 * - Push: TronProtocol pushes high-relevance memory summaries to edge nodes
 * - Pull: TronProtocol pulls observations from edge nodes into its RAG store
 * - Consolidation: During memory consolidation cycles, synchronize with all online nodes
 *
 * PicoClaw's MEMORY.md is a simple flat file. TronProtocol distills its
 * MemRL knowledge graph into concise summaries suitable for injection
 * into PicoClaw agent prompts.
 */
class SwarmMemorySync(
    private val ragStore: RAGStore,
    private val bridge: PicoClawBridgePlugin
) {
    companion object {
        private const val TAG = "SwarmMemorySync"
        private const val MAX_PUSH_ITEMS = 20
        private const val MIN_RELEVANCE_FOR_PUSH = 0.6f
        private const val MAX_SUMMARY_LENGTH = 2000
    }

    private val pushCount = AtomicLong(0)
    private val pullCount = AtomicLong(0)
    private val syncErrors = AtomicInteger(0)

    /**
     * Push high-relevance memories from TronProtocol's RAG to all online edge nodes.
     * Called during memory consolidation cycles.
     */
    fun pushToSwarm(): SyncResult {
        val nodes = bridge.getNodesByCapability(NodeCapability.MEMORY_STORAGE)
        if (nodes.isEmpty()) {
            return SyncResult(success = true, pushed = 0, pulled = 0,
                message = "No memory-capable nodes online")
        }

        val memories = extractPushableMemories()
        if (memories.isEmpty()) {
            return SyncResult(success = true, pushed = 0, pulled = 0,
                message = "No high-relevance memories to push")
        }

        var totalPushed = 0
        val errors = mutableListOf<String>()

        for (node in nodes) {
            try {
                val payload = JSONObject().apply {
                    put("source", "tronprotocol_rag")
                    put("timestamp", System.currentTimeMillis())
                    val items = JSONArray()
                    for ((content, relevance) in memories) {
                        items.put(JSONObject().apply {
                            put("content", content)
                            put("relevance", relevance.toDouble())
                        })
                    }
                    put("memories", items)
                }

                val msg = SwarmProtocol.SwarmMessage(
                    type = SwarmProtocol.MessageType.MEMORY_PUSH,
                    sourceNodeId = "tronprotocol_android",
                    targetNodeId = node.nodeId,
                    payload = payload
                )

                bridge.dispatchToNode(node, "/memory/push", msg.toJson().toString())
                node.recordSuccess()
                totalPushed += memories.size
                pushCount.addAndGet(memories.size.toLong())

                Log.d(TAG, "Pushed ${memories.size} memories to ${node.nodeId}")
            } catch (e: Exception) {
                node.recordFailure()
                errors.add("${node.nodeId}: ${e.message}")
                syncErrors.incrementAndGet()
            }
        }

        return SyncResult(
            success = errors.isEmpty(),
            pushed = totalPushed,
            pulled = 0,
            message = if (errors.isEmpty()) "Pushed to ${nodes.size} nodes"
                else "Partial push; errors: ${errors.joinToString("; ")}"
        )
    }

    /**
     * Pull observations from all online edge nodes into TronProtocol's RAG store.
     * PicoClaw nodes accumulate observations in their MEMORY.md files.
     */
    fun pullFromSwarm(): SyncResult {
        val nodes = bridge.getNodesByCapability(NodeCapability.MEMORY_STORAGE)
        if (nodes.isEmpty()) {
            return SyncResult(success = true, pushed = 0, pulled = 0,
                message = "No memory-capable nodes online")
        }

        var totalPulled = 0
        val errors = mutableListOf<String>()

        for (node in nodes) {
            try {
                val msg = SwarmProtocol.SwarmMessage(
                    type = SwarmProtocol.MessageType.MEMORY_PULL,
                    sourceNodeId = "tronprotocol_android",
                    targetNodeId = node.nodeId
                )

                val response = bridge.dispatchToNode(node, "/memory/pull", msg.toJson().toString())
                val json = JSONObject(response)
                val memories = json.optJSONArray("memories")

                if (memories != null) {
                    for (i in 0 until memories.length()) {
                        val item = memories.getJSONObject(i)
                        val content = item.optString("content", "")
                        val relevance = item.optDouble("relevance", 0.5).toFloat()

                        if (content.isNotBlank()) {
                            val taggedContent = "[swarm:${node.nodeId}] $content"
                            ragStore.addMemory(taggedContent, relevance)
                            totalPulled++
                        }
                    }
                }

                node.recordSuccess()
                pullCount.addAndGet(totalPulled.toLong())
                Log.d(TAG, "Pulled $totalPulled memories from ${node.nodeId}")
            } catch (e: Exception) {
                node.recordFailure()
                errors.add("${node.nodeId}: ${e.message}")
                syncErrors.incrementAndGet()
            }
        }

        return SyncResult(
            success = errors.isEmpty(),
            pushed = 0,
            pulled = totalPulled,
            message = if (errors.isEmpty()) "Pulled from ${nodes.size} nodes"
                else "Partial pull; errors: ${errors.joinToString("; ")}"
        )
    }

    /**
     * Full bidirectional sync: push then pull.
     */
    fun fullSync(): SyncResult {
        val pushResult = pushToSwarm()
        val pullResult = pullFromSwarm()

        return SyncResult(
            success = pushResult.success && pullResult.success,
            pushed = pushResult.pushed,
            pulled = pullResult.pulled,
            message = "Push: ${pushResult.message} | Pull: ${pullResult.message}"
        )
    }

    // ========================================================================
    // Memory extraction
    // ========================================================================

    private fun extractPushableMemories(): List<Pair<String, Float>> {
        val results = mutableListOf<Pair<String, Float>>()
        try {
            val stats = ragStore.getMemRLStats()
            val totalChunks = (stats["total_chunks"] as? Int) ?: 0
            if (totalChunks == 0) return results

            // Retrieve top memories by MemRL Q-value
            val topMemories = ragStore.retrieve(
                "important observations and decisions",
                com.tronprotocol.app.rag.RetrievalStrategy.MEMRL,
                MAX_PUSH_ITEMS
            )

            for (result in topMemories) {
                val content = result.chunk.content
                if (content.length <= MAX_SUMMARY_LENGTH) {
                    results.add(content to result.score)
                } else {
                    results.add(content.take(MAX_SUMMARY_LENGTH) to result.score)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract pushable memories: ${e.message}")
        }
        return results
    }

    // ========================================================================
    // Stats
    // ========================================================================

    fun getStats(): Map<String, Any> = mapOf(
        "total_pushed" to pushCount.get(),
        "total_pulled" to pullCount.get(),
        "sync_errors" to syncErrors.get(),
        "memory_capable_nodes" to bridge.getNodesByCapability(NodeCapability.MEMORY_STORAGE).size
    )

    data class SyncResult(
        val success: Boolean,
        val pushed: Int,
        val pulled: Int,
        val message: String
    )
}
