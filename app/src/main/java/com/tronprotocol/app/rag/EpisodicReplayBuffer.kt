package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured episode storage for experiential learning.
 * Each episode captures: perception -> decision -> action -> outcome.
 * Enables offline learning from complete interaction cycles.
 */
class EpisodicReplayBuffer(context: Context, private val aiId: String) {

    private val storage = SecureStorage(context)
    private val episodes = mutableListOf<Episode>()
    private val maxEpisodes = 500

    init { loadEpisodes() }

    fun recordEpisode(
        perception: String,
        decision: String,
        action: String,
        outcome: String,
        reward: Float,
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val episode = Episode(
            id = "ep_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            perception = perception,
            decision = decision,
            action = action,
            outcome = outcome,
            reward = reward,
            metadata = metadata.toMutableMap()
        )
        episodes.add(episode)
        evictIfNeeded()
        saveEpisodes()
        return episode.id
    }

    fun getRecent(count: Int): List<Episode> =
        episodes.sortedByDescending { it.timestamp }.take(count)

    fun getHighReward(count: Int, minReward: Float = 0.7f): List<Episode> =
        episodes.filter { it.reward >= minReward }.sortedByDescending { it.reward }.take(count)

    fun getLowReward(count: Int, maxReward: Float = 0.3f): List<Episode> =
        episodes.filter { it.reward <= maxReward }.sortedBy { it.reward }.take(count)

    fun search(keyword: String, count: Int = 20): List<Episode> {
        val kw = keyword.lowercase()
        return episodes.filter {
            it.perception.lowercase().contains(kw) ||
                    it.decision.lowercase().contains(kw) ||
                    it.action.lowercase().contains(kw) ||
                    it.outcome.lowercase().contains(kw)
        }.sortedByDescending { it.timestamp }.take(count)
    }

    fun getByAction(actionType: String, count: Int = 20): List<Episode> =
        episodes.filter { it.action.lowercase().contains(actionType.lowercase()) }
            .sortedByDescending { it.timestamp }.take(count)

    fun updateReward(episodeId: String, newReward: Float): Boolean {
        val ep = episodes.find { it.id == episodeId } ?: return false
        ep.reward = newReward
        saveEpisodes()
        return true
    }

    fun getStats(): Map<String, Any> = mapOf(
        "total_episodes" to episodes.size,
        "avg_reward" to if (episodes.isNotEmpty()) episodes.map { it.reward }.average() else 0.0,
        "positive_episodes" to episodes.count { it.reward > 0.5f },
        "negative_episodes" to episodes.count { it.reward < 0.5f },
        "oldest_timestamp" to (episodes.minOfOrNull { it.timestamp } ?: 0L),
        "newest_timestamp" to (episodes.maxOfOrNull { it.timestamp } ?: 0L)
    )

    fun clear() {
        episodes.clear()
        storage.delete("episodic_replay_$aiId")
    }

    private fun evictIfNeeded() {
        if (episodes.size <= maxEpisodes) return
        episodes.sortBy { it.reward }
        val toRemove = episodes.size - maxEpisodes
        repeat(toRemove) { episodes.removeAt(0) }
    }

    private fun saveEpisodes() {
        try {
            val arr = JSONArray()
            for (ep in episodes) {
                arr.put(JSONObject().apply {
                    put("id", ep.id)
                    put("timestamp", ep.timestamp)
                    put("perception", ep.perception)
                    put("decision", ep.decision)
                    put("action", ep.action)
                    put("outcome", ep.outcome)
                    put("reward", ep.reward.toDouble())
                    val meta = JSONObject()
                    ep.metadata.forEach { (k, v) -> meta.put(k, v) }
                    put("metadata", meta)
                })
            }
            storage.store("episodic_replay_$aiId", arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save episodes", e)
        }
    }

    private fun loadEpisodes() {
        try {
            val data = storage.retrieve("episodic_replay_$aiId") ?: return
            val arr = JSONArray(data)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val meta = mutableMapOf<String, Any>()
                val metaObj = obj.optJSONObject("metadata")
                metaObj?.keys()?.forEach { k -> meta[k] = metaObj.get(k) }
                episodes.add(Episode(
                    id = obj.getString("id"),
                    timestamp = obj.getLong("timestamp"),
                    perception = obj.getString("perception"),
                    decision = obj.getString("decision"),
                    action = obj.getString("action"),
                    outcome = obj.getString("outcome"),
                    reward = obj.getDouble("reward").toFloat(),
                    metadata = meta
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load episodes", e)
        }
    }

    data class Episode(
        val id: String,
        val timestamp: Long,
        val perception: String,
        val decision: String,
        val action: String,
        val outcome: String,
        var reward: Float,
        val metadata: MutableMap<String, Any> = mutableMapOf()
    )

    companion object {
        private const val TAG = "EpisodicReplayBuffer"
    }
}
