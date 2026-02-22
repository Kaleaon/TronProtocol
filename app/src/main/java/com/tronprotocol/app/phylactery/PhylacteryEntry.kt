package com.tronprotocol.app.phylactery

import org.json.JSONObject
import java.security.MessageDigest

/**
 * A single entry in the Phylactery memory system.
 *
 * Each entry belongs to a [MemoryTier] and carries an embedding vector
 * for semantic retrieval, along with metadata for consolidation and drift detection.
 */
data class PhylacteryEntry(
    val id: String,
    val tier: MemoryTier,
    val content: String,
    val embedding: FloatArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val emotionalSnapshot: Map<String, Float>? = null,
    val metadata: MutableMap<String, Any> = mutableMapOf(),
    val contentHash: String = computeHash(content)
) {
    /** Q-value for MemRL-style memory evolution. Higher = more useful. */
    var qValue: Float = 0.5f

    /** Drift score: cosine distance between recalled and ground-truth versions. */
    var driftScore: Float = 0.0f

    /** Number of times this entry has been retrieved. */
    var retrievalCount: Int = 0

    /** Last time this entry was accessed (epoch ms). */
    var lastAccessTime: Long = timestamp

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tier", tier.label)
        put("content", content)
        put("timestamp", timestamp)
        put("session_id", sessionId ?: "")
        put("q_value", qValue.toDouble())
        put("drift_score", driftScore.toDouble())
        put("retrieval_count", retrievalCount)
        put("last_access_time", lastAccessTime)
        put("content_hash", contentHash)
        if (emotionalSnapshot != null) {
            val snapJson = JSONObject()
            emotionalSnapshot.forEach { (k, v) -> snapJson.put(k, v.toDouble()) }
            put("emotional_snapshot", snapJson)
        }
        if (embedding != null) {
            val embStr = embedding.joinToString(",") { "%.6f".format(it) }
            put("embedding", embStr)
        }
        val meta = JSONObject()
        metadata.forEach { (k, v) -> meta.put(k, v.toString()) }
        put("metadata", meta)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhylacteryEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun computeHash(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        fun fromJson(json: JSONObject): PhylacteryEntry {
            val tier = MemoryTier.fromLabel(json.optString("tier", "working"))
                ?: MemoryTier.WORKING
            val embStr = json.optString("embedding", "")
            val embedding = if (embStr.isNotEmpty()) {
                embStr.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
            } else null

            val emotionalSnapshot = if (json.has("emotional_snapshot")) {
                val snapJson = json.getJSONObject("emotional_snapshot")
                val map = mutableMapOf<String, Float>()
                snapJson.keys().forEach { key ->
                    map[key] = snapJson.getDouble(key).toFloat()
                }
                map
            } else null

            val entry = PhylacteryEntry(
                id = json.getString("id"),
                tier = tier,
                content = json.getString("content"),
                embedding = embedding,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sessionId = json.optString("session_id", null),
                emotionalSnapshot = emotionalSnapshot,
                contentHash = json.optString("content_hash", "")
            )
            entry.qValue = json.optDouble("q_value", 0.5).toFloat()
            entry.driftScore = json.optDouble("drift_score", 0.0).toFloat()
            entry.retrievalCount = json.optInt("retrieval_count", 0)
            entry.lastAccessTime = json.optLong("last_access_time", entry.timestamp)

            if (json.has("metadata")) {
                val meta = json.getJSONObject("metadata")
                meta.keys().forEach { key ->
                    entry.metadata[key] = meta.getString(key)
                }
            }
            return entry
        }
    }
}
