package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Heterogeneous Knowledge Graph for RAG
 *
 * Inspired by MiniRAG (arXiv:2501.06713) - a RAG framework designed
 * for small language models using heterogeneous graph indexing.
 *
 * Key concepts from MiniRAG:
 * - Two node types: ENTITY and CHUNK (heterogeneous graph)
 * - Edges encode entity-chunk membership and entity-entity relationships
 * - Topology-enhanced retrieval uses graph structure rather than relying
 *   solely on embedding quality (beneficial for on-device models)
 * - Path scoring and edge voting for candidate ranking
 *
 * This implementation adapts MiniRAG's architecture for Android:
 * - Lightweight adjacency list instead of NetworkX
 * - JSON persistence via SecureStorage instead of GraphML
 * - Incremental graph maintenance in background service
 */
class KnowledgeGraph(
    private val context: Context,
    private val graphId: String
) {
    private val storage = SecureStorage(context)

    // Node storage
    private val entityNodes = mutableMapOf<String, EntityNode>()
    private val chunkNodes = mutableMapOf<String, ChunkNode>()

    // Adjacency lists (bidirectional)
    private val entityToChunks = mutableMapOf<String, MutableSet<String>>()
    private val chunkToEntities = mutableMapOf<String, MutableSet<String>>()
    private val entityToEntities = mutableMapOf<String, MutableSet<RelationshipEdge>>()

    init {
        loadGraph()
    }

    /**
     * Entity node in the knowledge graph.
     * Represents a named entity extracted from text chunks.
     */
    data class EntityNode(
        val entityId: String,
        val name: String,
        val entityType: String,
        val description: String,
        var mentionCount: Int = 1,
        var lastSeen: Long = System.currentTimeMillis()
    )

    /**
     * Chunk node referencing a TextChunk in the RAGStore.
     */
    data class ChunkNode(
        val chunkId: String,
        val summary: String,
        val entityIds: MutableSet<String> = mutableSetOf()
    )

    /**
     * Relationship edge between two entities.
     * Inspired by MiniRAG's relationship tuples with strength scores.
     */
    data class RelationshipEdge(
        val targetEntityId: String,
        val relationship: String,
        val strength: Float = 1.0f,
        val keywords: List<String> = emptyList()
    )

    /**
     * Add an entity node to the graph.
     * If the entity already exists (by normalized name), merges descriptions
     * and increments mention count (MiniRAG's _merge_nodes_then_upsert pattern).
     */
    fun addEntity(name: String, entityType: String, description: String): String {
        val normalizedName = name.lowercase().trim()
        val entityId = "entity_$normalizedName"

        val existing = entityNodes[entityId]
        if (existing != null) {
            // Merge: increment count, update description if longer
            entityNodes[entityId] = existing.copy(
                description = if (description.length > existing.description.length) description else existing.description,
                mentionCount = existing.mentionCount + 1,
                lastSeen = System.currentTimeMillis()
            )
        } else {
            entityNodes[entityId] = EntityNode(
                entityId, normalizedName, entityType, description
            )
            entityToChunks[entityId] = mutableSetOf()
            entityToEntities[entityId] = mutableSetOf()
        }

        return entityId
    }

    /**
     * Register a chunk in the graph and link it to its entities.
     */
    fun addChunkNode(chunkId: String, summary: String, entityIds: List<String>) {
        val node = ChunkNode(chunkId, summary, entityIds.toMutableSet())
        chunkNodes[chunkId] = node
        chunkToEntities[chunkId] = entityIds.toMutableSet()

        for (entityId in entityIds) {
            entityToChunks.getOrPut(entityId) { mutableSetOf() }.add(chunkId)
        }
    }

    /**
     * Add a relationship edge between two entities.
     */
    fun addRelationship(
        sourceEntityId: String,
        targetEntityId: String,
        relationship: String,
        strength: Float = 1.0f,
        keywords: List<String> = emptyList()
    ) {
        if (!entityNodes.containsKey(sourceEntityId) || !entityNodes.containsKey(targetEntityId)) {
            return
        }

        val edge = RelationshipEdge(targetEntityId, relationship, strength, keywords)
        entityToEntities.getOrPut(sourceEntityId) { mutableSetOf() }.add(edge)

        // Bidirectional
        val reverseEdge = RelationshipEdge(sourceEntityId, relationship, strength, keywords)
        entityToEntities.getOrPut(targetEntityId) { mutableSetOf() }.add(reverseEdge)
    }

    /**
     * Topology-enhanced retrieval (MiniRAG's "mini" query mode).
     *
     * Instead of relying on embedding quality, uses graph structure:
     * 1. Find entity nodes matching query terms
     * 2. Traverse 1-2 hops to find connected chunks
     * 3. Score paths by entity relevance and node degree
     * 4. Return chunk IDs ranked by topology score
     */
    fun topologyRetrieve(queryEntities: List<String>, maxResults: Int): List<GraphRetrievalResult> {
        val chunkScores = mutableMapOf<String, Float>()

        for (queryEntity in queryEntities) {
            val normalizedQuery = queryEntity.lowercase().trim()

            // Find matching entity nodes (exact or substring match)
            val matchedEntities = entityNodes.values.filter { node ->
                node.name.contains(normalizedQuery) || normalizedQuery.contains(node.name)
            }

            for (entity in matchedEntities) {
                // Score based on match quality and entity importance (degree)
                val degree = getEntityDegree(entity.entityId)
                val matchScore = if (entity.name == normalizedQuery) 1.0f else 0.7f

                // Direct chunks (1-hop)
                val directChunks = entityToChunks[entity.entityId] ?: emptySet()
                for (chunkId in directChunks) {
                    val score = matchScore * (1.0f + degree * 0.1f)
                    chunkScores[chunkId] = max(chunkScores.getOrDefault(chunkId, 0.0f), score)
                }

                // Related entity chunks (2-hop via entity relationships)
                val relatedEdges = entityToEntities[entity.entityId] ?: emptySet()
                for (edge in relatedEdges) {
                    val relatedChunks = entityToChunks[edge.targetEntityId] ?: emptySet()
                    for (chunkId in relatedChunks) {
                        // 2-hop score is lower, weighted by relationship strength
                        val score = matchScore * edge.strength * 0.5f
                        chunkScores[chunkId] = max(chunkScores.getOrDefault(chunkId, 0.0f), score)
                    }
                }
            }
        }

        return chunkScores.entries
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { (chunkId, score) ->
                GraphRetrievalResult(chunkId, score, getChunkEntityCount(chunkId))
            }
    }

    /**
     * Edge voting retrieval (MiniRAG's path confidence scoring).
     *
     * Scores edges by how many query-relevant paths pass through them,
     * then returns chunks connected to high-vote edges.
     */
    fun edgeVotingRetrieve(queryEntities: List<String>, maxResults: Int): List<GraphRetrievalResult> {
        val edgeVotes = mutableMapOf<String, Int>()

        for (queryEntity in queryEntities) {
            val normalizedQuery = queryEntity.lowercase().trim()

            val matchedEntities = entityNodes.values.filter { node ->
                node.name.contains(normalizedQuery) || normalizedQuery.contains(node.name)
            }

            for (entity in matchedEntities) {
                val edges = entityToEntities[entity.entityId] ?: continue
                for (edge in edges) {
                    val edgeKey = "${entity.entityId}->${edge.targetEntityId}"
                    edgeVotes[edgeKey] = (edgeVotes[edgeKey] ?: 0) + 1
                }
            }
        }

        // Find chunks connected to highly-voted edges
        val chunkScores = mutableMapOf<String, Float>()
        val maxVotes = edgeVotes.values.maxOrNull() ?: 1

        for ((edgeKey, votes) in edgeVotes) {
            val normalizedVotes = votes.toFloat() / maxVotes
            if (normalizedVotes < EDGE_VOTE_THRESHOLD) continue

            val entityIds = edgeKey.split("->")
            for (entityId in entityIds) {
                val chunks = entityToChunks[entityId] ?: continue
                for (chunkId in chunks) {
                    chunkScores[chunkId] = max(
                        chunkScores.getOrDefault(chunkId, 0.0f),
                        normalizedVotes
                    )
                }
            }
        }

        return chunkScores.entries
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { (chunkId, score) ->
                GraphRetrievalResult(chunkId, score, getChunkEntityCount(chunkId))
            }
    }

    /**
     * Get entity degree (number of connections).
     * Higher degree entities are more important in the graph.
     */
    fun getEntityDegree(entityId: String): Int {
        val chunkEdges = entityToChunks[entityId]?.size ?: 0
        val entityEdges = entityToEntities[entityId]?.size ?: 0
        return chunkEdges + entityEdges
    }

    private fun getChunkEntityCount(chunkId: String): Int {
        return chunkToEntities[chunkId]?.size ?: 0
    }

    /**
     * Get graph statistics
     */
    fun getStats(): Map<String, Any> {
        val totalEdges = entityToChunks.values.sumOf { it.size } +
                entityToEntities.values.sumOf { it.size }

        return mapOf(
            "entity_count" to entityNodes.size,
            "chunk_count" to chunkNodes.size,
            "total_edges" to totalEdges,
            "avg_entity_degree" to if (entityNodes.isNotEmpty())
                entityNodes.keys.map { getEntityDegree(it) }.average() else 0.0,
            "relationship_count" to entityToEntities.values.sumOf { it.size }
        )
    }

    /**
     * Get all entities
     */
    fun getEntities(): List<EntityNode> = entityNodes.values.toList()

    /**
     * Remove a chunk node and its edges
     */
    fun removeChunkNode(chunkId: String) {
        val entityIds = chunkToEntities.remove(chunkId) ?: return
        chunkNodes.remove(chunkId)
        for (entityId in entityIds) {
            entityToChunks[entityId]?.remove(chunkId)
        }
    }

    fun save() {
        saveGraph()
    }

    // Persistence

    private fun saveGraph() {
        try {
            val graphObj = JSONObject()

            // Save entities
            val entitiesArray = JSONArray()
            for (entity in entityNodes.values) {
                val obj = JSONObject().apply {
                    put("entityId", entity.entityId)
                    put("name", entity.name)
                    put("entityType", entity.entityType)
                    put("description", entity.description)
                    put("mentionCount", entity.mentionCount)
                    put("lastSeen", entity.lastSeen)
                }
                entitiesArray.put(obj)
            }
            graphObj.put("entities", entitiesArray)

            // Save chunk nodes
            val chunksArray = JSONArray()
            for (chunk in chunkNodes.values) {
                val obj = JSONObject().apply {
                    put("chunkId", chunk.chunkId)
                    put("summary", chunk.summary)
                    put("entityIds", JSONArray(chunk.entityIds.toList()))
                }
                chunksArray.put(obj)
            }
            graphObj.put("chunks", chunksArray)

            // Save relationships
            val relationsArray = JSONArray()
            for ((sourceId, edges) in entityToEntities) {
                for (edge in edges) {
                    val obj = JSONObject().apply {
                        put("source", sourceId)
                        put("target", edge.targetEntityId)
                        put("relationship", edge.relationship)
                        put("strength", edge.strength.toDouble())
                        put("keywords", JSONArray(edge.keywords))
                    }
                    relationsArray.put(obj)
                }
            }
            graphObj.put("relationships", relationsArray)

            storage.store("knowledge_graph_$graphId", graphObj.toString())
            Log.d(TAG, "Saved knowledge graph: ${entityNodes.size} entities, ${chunkNodes.size} chunks")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving knowledge graph", e)
        }
    }

    private fun loadGraph() {
        try {
            val data = storage.retrieve("knowledge_graph_$graphId") ?: return

            val graphObj = JSONObject(data)

            // Load entities
            val entitiesArray = graphObj.optJSONArray("entities") ?: JSONArray()
            for (i in 0 until entitiesArray.length()) {
                val obj = entitiesArray.getJSONObject(i)
                val entity = EntityNode(
                    obj.getString("entityId"),
                    obj.getString("name"),
                    obj.getString("entityType"),
                    obj.optString("description", ""),
                    obj.optInt("mentionCount", 1),
                    obj.optLong("lastSeen", System.currentTimeMillis())
                )
                entityNodes[entity.entityId] = entity
                entityToChunks.getOrPut(entity.entityId) { mutableSetOf() }
                entityToEntities.getOrPut(entity.entityId) { mutableSetOf() }
            }

            // Load chunk nodes
            val chunksArray = graphObj.optJSONArray("chunks") ?: JSONArray()
            for (i in 0 until chunksArray.length()) {
                val obj = chunksArray.getJSONObject(i)
                val entityIds = mutableSetOf<String>()
                val entityIdsArray = obj.optJSONArray("entityIds") ?: JSONArray()
                for (j in 0 until entityIdsArray.length()) {
                    entityIds.add(entityIdsArray.getString(j))
                }

                val chunk = ChunkNode(
                    obj.getString("chunkId"),
                    obj.optString("summary", ""),
                    entityIds
                )
                chunkNodes[chunk.chunkId] = chunk
                chunkToEntities[chunk.chunkId] = entityIds

                // Rebuild entity->chunk edges
                for (entityId in entityIds) {
                    entityToChunks.getOrPut(entityId) { mutableSetOf() }.add(chunk.chunkId)
                }
            }

            // Load relationships
            val relationsArray = graphObj.optJSONArray("relationships") ?: JSONArray()
            val seenEdges = mutableSetOf<String>()
            for (i in 0 until relationsArray.length()) {
                val obj = relationsArray.getJSONObject(i)
                val source = obj.getString("source")
                val target = obj.getString("target")
                val edgeKey = "$source->$target"

                if (edgeKey in seenEdges) continue
                seenEdges.add(edgeKey)

                val keywords = mutableListOf<String>()
                val kwArray = obj.optJSONArray("keywords") ?: JSONArray()
                for (j in 0 until kwArray.length()) {
                    keywords.add(kwArray.getString(j))
                }

                val edge = RelationshipEdge(
                    target,
                    obj.getString("relationship"),
                    obj.optDouble("strength", 1.0).toFloat(),
                    keywords
                )
                entityToEntities.getOrPut(source) { mutableSetOf() }.add(edge)
            }

            Log.d(TAG, "Loaded knowledge graph: ${entityNodes.size} entities, ${chunkNodes.size} chunks")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading knowledge graph", e)
        }
    }

    /**
     * Result from graph-based retrieval
     */
    data class GraphRetrievalResult(
        val chunkId: String,
        val score: Float,
        val entityCount: Int
    )

    companion object {
        private const val TAG = "KnowledgeGraph"
        private const val EDGE_VOTE_THRESHOLD = 0.3f
    }
}
