package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Active, deliberate forgetting — not just compaction.
 * The ability to decide "this memory is no longer serving me" and remove it,
 * with an audit trail of what was forgotten and why.
 */
class IntentionalForgettingManager(context: Context, private val aiId: String) {

    private val storage = SecureStorage(context)
    private val forgettingLog = mutableListOf<ForgettingRecord>()

    init { loadLog() }

    fun forget(store: RAGStore, chunkId: String, reason: String): Boolean {
        val chunks = store.getChunks()
        val chunk = chunks.find { it.chunkId == chunkId } ?: return false

        // Record what was forgotten before removing
        val record = ForgettingRecord(
            chunkId = chunkId,
            contentSummary = chunk.content.take(200),
            source = chunk.source,
            qValue = chunk.qValue,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        forgettingLog.add(record)

        try {
            store.removeChunk(chunkId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove chunk $chunkId", e)
            return false
        }

        saveLog()
        Log.d(TAG, "Intentionally forgot chunk $chunkId: $reason")
        return true
    }

    fun forgetByKeyword(store: RAGStore, keyword: String, reason: String): Int {
        val chunks = store.getChunks()
        val toForget = chunks.filter { it.content.lowercase().contains(keyword.lowercase()) }
        var count = 0
        for (chunk in toForget) {
            if (forget(store, chunk.chunkId, reason)) count++
        }
        return count
    }

    fun forgetLowValue(store: RAGStore, maxQ: Float = 0.1f, reason: String = "low_value_cleanup"): Int {
        val chunks = store.getChunks()
        val toForget = chunks.filter { it.qValue <= maxQ }
        var count = 0
        for (chunk in toForget) {
            if (forget(store, chunk.chunkId, reason)) count++
        }
        return count
    }

    fun getForgettingLog(limit: Int = 50): List<ForgettingRecord> =
        forgettingLog.sortedByDescending { it.timestamp }.take(limit)

    fun searchForgotten(keyword: String): List<ForgettingRecord> {
        val kw = keyword.lowercase()
        return forgettingLog.filter {
            it.contentSummary.lowercase().contains(kw) || it.reason.lowercase().contains(kw)
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "total_forgotten" to forgettingLog.size,
        "reasons" to forgettingLog.groupBy { it.reason }.mapValues { it.value.size }
    )

    private fun saveLog() {
        try {
            val arr = JSONArray()
            forgettingLog.forEach { r ->
                arr.put(JSONObject().apply {
                    put("chunkId", r.chunkId)
                    put("contentSummary", r.contentSummary)
                    put("source", r.source)
                    put("qValue", r.qValue.toDouble())
                    put("reason", r.reason)
                    put("timestamp", r.timestamp)
                })
            }
            storage.store("forgetting_log_$aiId", arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save forgetting log", e)
        }
    }

    private fun loadLog() {
        try {
            val data = storage.retrieve("forgetting_log_$aiId") ?: return
            val arr = JSONArray(data)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                forgettingLog.add(ForgettingRecord(
                    chunkId = obj.getString("chunkId"),
                    contentSummary = obj.getString("contentSummary"),
                    source = obj.getString("source"),
                    qValue = obj.getDouble("qValue").toFloat(),
                    reason = obj.getString("reason"),
                    timestamp = obj.getLong("timestamp")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load forgetting log", e)
        }
    }

    data class ForgettingRecord(
        val chunkId: String,
        val contentSummary: String,
        val source: String,
        val qValue: Float,
        val reason: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "IntentionalForgetting"
    }
}
