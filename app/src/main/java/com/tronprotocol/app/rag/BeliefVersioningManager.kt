package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Semantic versioning of beliefs. Tracks how understanding evolves over time.
 * When a belief is updated, the old version is preserved with context for why it changed.
 */
class BeliefVersioningManager(context: Context, private val aiId: String) {

    private val storage = SecureStorage(context)
    private val beliefs = mutableMapOf<String, BeliefHistory>()

    init { load() }

    fun setBelief(topic: String, belief: String, confidence: Float, reason: String): Int {
        val history = beliefs.getOrPut(topic) {
            BeliefHistory(topic, mutableListOf())
        }
        val version = history.versions.size + 1
        history.versions.add(BeliefVersion(
            version = version,
            belief = belief,
            confidence = confidence,
            reason = reason,
            timestamp = System.currentTimeMillis()
        ))
        save()
        return version
    }

    fun getBelief(topic: String): BeliefVersion? =
        beliefs[topic]?.versions?.lastOrNull()

    fun getBeliefHistory(topic: String): List<BeliefVersion> =
        beliefs[topic]?.versions?.toList() ?: emptyList()

    fun getAllTopics(): Set<String> = beliefs.keys.toSet()

    fun getChangedBeliefs(sinceTimestamp: Long): List<Pair<String, BeliefVersion>> {
        val results = mutableListOf<Pair<String, BeliefVersion>>()
        for ((topic, history) in beliefs) {
            val recent = history.versions.filter { it.timestamp >= sinceTimestamp }
            recent.forEach { results.add(Pair(topic, it)) }
        }
        return results.sortedByDescending { it.second.timestamp }
    }

    fun searchBeliefs(keyword: String): List<Pair<String, BeliefVersion>> {
        val kw = keyword.lowercase()
        return beliefs.entries
            .filter { (topic, history) ->
                topic.lowercase().contains(kw) ||
                        history.versions.any { it.belief.lowercase().contains(kw) }
            }
            .map { (topic, history) -> Pair(topic, history.versions.last()) }
    }

    fun removeBelief(topic: String): Boolean {
        val removed = beliefs.remove(topic) != null
        if (removed) save()
        return removed
    }

    fun getStats(): Map<String, Any> = mapOf(
        "total_topics" to beliefs.size,
        "total_versions" to beliefs.values.sumOf { it.versions.size },
        "avg_versions_per_topic" to if (beliefs.isNotEmpty()) beliefs.values.map { it.versions.size }.average() else 0.0,
        "most_revised" to (beliefs.maxByOrNull { it.value.versions.size }?.key ?: "none")
    )

    private fun save() {
        try {
            val obj = JSONObject()
            for ((topic, history) in beliefs) {
                val versions = JSONArray()
                history.versions.forEach { v ->
                    versions.put(JSONObject().apply {
                        put("version", v.version)
                        put("belief", v.belief)
                        put("confidence", v.confidence.toDouble())
                        put("reason", v.reason)
                        put("timestamp", v.timestamp)
                    })
                }
                obj.put(topic, versions)
            }
            storage.store("belief_versions_$aiId", obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save beliefs", e)
        }
    }

    private fun load() {
        try {
            val data = storage.retrieve("belief_versions_$aiId") ?: return
            val obj = JSONObject(data)
            obj.keys().forEach { topic ->
                val versions = obj.getJSONArray(topic)
                val vList = mutableListOf<BeliefVersion>()
                for (i in 0 until versions.length()) {
                    val v = versions.getJSONObject(i)
                    vList.add(BeliefVersion(
                        version = v.getInt("version"),
                        belief = v.getString("belief"),
                        confidence = v.getDouble("confidence").toFloat(),
                        reason = v.getString("reason"),
                        timestamp = v.getLong("timestamp")
                    ))
                }
                beliefs[topic] = BeliefHistory(topic, vList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load beliefs", e)
        }
    }

    data class BeliefVersion(
        val version: Int,
        val belief: String,
        val confidence: Float,
        val reason: String,
        val timestamp: Long
    )

    data class BeliefHistory(
        val topic: String,
        val versions: MutableList<BeliefVersion>
    )

    companion object {
        private const val TAG = "BeliefVersioning"
    }
}
