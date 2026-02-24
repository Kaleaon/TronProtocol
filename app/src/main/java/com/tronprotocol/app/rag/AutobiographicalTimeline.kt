package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chronologically indexed personal history with milestone detection.
 * Tracks firsts, recurring patterns, and significant changes.
 */
class AutobiographicalTimeline(context: Context, private val aiId: String) {

    private val storage = SecureStorage(context)
    private val milestones = mutableListOf<Milestone>()
    private val firsts = mutableMapOf<String, Long>() // category -> first occurrence timestamp
    @Volatile private var lastTimestamp = 0L

    init { load() }

    fun recordMilestone(
        category: String,
        description: String,
        significance: Float = 0.5f,
        metadata: Map<String, Any> = emptyMap()
    ): Milestone {
        val isFirst = !firsts.containsKey(category)
        // Ensure strictly increasing timestamps even when called in the same millisecond
        val ts = synchronized(this) {
            val now = System.currentTimeMillis()
            lastTimestamp = if (now > lastTimestamp) now else lastTimestamp + 1
            lastTimestamp
        }
        if (isFirst) firsts[category] = ts

        val milestone = Milestone(
            id = "ms_$ts",
            timestamp = ts,
            category = category,
            description = description,
            significance = significance,
            isFirst = isFirst,
            metadata = metadata.toMutableMap()
        )
        milestones.add(milestone)

        if (milestones.size > MAX_MILESTONES) {
            milestones.sortBy { it.significance }
            milestones.removeAt(0)
        }

        save()
        return milestone
    }

    fun getTimeline(limit: Int = 50): List<Milestone> =
        milestones.sortedByDescending { it.timestamp }.take(limit)

    fun getByCategory(category: String): List<Milestone> =
        milestones.filter { it.category == category }.sortedByDescending { it.timestamp }

    fun getFirsts(): Map<String, Long> = firsts.toMap()

    fun getMostSignificant(count: Int = 10): List<Milestone> =
        milestones.sortedByDescending { it.significance }.take(count)

    fun generateNarrative(): String {
        val totalDays = if (milestones.isNotEmpty()) {
            val oldest = milestones.minOf { it.timestamp }
            ((System.currentTimeMillis() - oldest) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
        } else 0L

        val categories = milestones.groupBy { it.category }
        val topCategories = categories.entries.sortedByDescending { it.value.size }.take(5)
        val firstsList = firsts.entries.sortedBy { it.value }.take(10)

        return buildString {
            append("Autobiographical Summary (${totalDays} days active, ${milestones.size} milestones):\n")
            append("Categories: ${categories.size}\n")
            append("Top activities: ${topCategories.joinToString { "${it.key}(${it.value.size})" }}\n")
            if (firstsList.isNotEmpty()) {
                append("Notable firsts: ${firstsList.joinToString { it.key }}\n")
            }
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "total_milestones" to milestones.size,
        "total_categories" to milestones.map { it.category }.distinct().size,
        "total_firsts" to firsts.size,
        "avg_significance" to if (milestones.isNotEmpty()) milestones.map { it.significance }.average() else 0.0
    )

    private fun save() {
        try {
            val obj = JSONObject()
            val msArr = JSONArray()
            milestones.forEach { ms ->
                msArr.put(JSONObject().apply {
                    put("id", ms.id)
                    put("timestamp", ms.timestamp)
                    put("category", ms.category)
                    put("description", ms.description)
                    put("significance", ms.significance.toDouble())
                    put("isFirst", ms.isFirst)
                    val meta = JSONObject()
                    ms.metadata.forEach { (k, v) -> meta.put(k, v) }
                    put("metadata", meta)
                })
            }
            obj.put("milestones", msArr)
            val firstsObj = JSONObject()
            firsts.forEach { (k, v) -> firstsObj.put(k, v) }
            obj.put("firsts", firstsObj)
            storage.store("autobio_timeline_$aiId", obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save timeline", e)
        }
    }

    private fun load() {
        try {
            val data = storage.retrieve("autobio_timeline_$aiId") ?: return
            val obj = JSONObject(data)
            val msArr = obj.optJSONArray("milestones") ?: return
            for (i in 0 until msArr.length()) {
                val ms = msArr.getJSONObject(i)
                val meta = mutableMapOf<String, Any>()
                ms.optJSONObject("metadata")?.let { m -> m.keys().forEach { k -> meta[k] = m.get(k) } }
                milestones.add(Milestone(
                    id = ms.getString("id"),
                    timestamp = ms.getLong("timestamp"),
                    category = ms.getString("category"),
                    description = ms.getString("description"),
                    significance = ms.getDouble("significance").toFloat(),
                    isFirst = ms.optBoolean("isFirst", false),
                    metadata = meta
                ))
            }
            val firstsObj = obj.optJSONObject("firsts")
            firstsObj?.keys()?.forEach { k -> firsts[k] = firstsObj.getLong(k) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load timeline", e)
        }
    }

    data class Milestone(
        val id: String,
        val timestamp: Long,
        val category: String,
        val description: String,
        val significance: Float,
        val isFirst: Boolean,
        val metadata: MutableMap<String, Any> = mutableMapOf()
    )

    companion object {
        private const val TAG = "AutobioTimeline"
        private const val MAX_MILESTONES = 1000
    }
}
