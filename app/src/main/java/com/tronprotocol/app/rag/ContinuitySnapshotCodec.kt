package com.tronprotocol.app.rag

import org.json.JSONObject

/**
 * Encodes/decodes portable continuity snapshots so memories and traits can be restored
 * after crashes, reinstalls, or model upgrades.
 */
object ContinuitySnapshotCodec {
    private const val SCHEMA_VERSION = 1

    data class ContinuitySnapshot(
        val snapshotId: String,
        val aiId: String,
        val createdAtMs: Long,
        val ragChunksJson: String?,
        val emotionalHistoryJson: String?,
        val personalityTraitsJson: String?,
        val consolidationStatsJson: String?,
        val constitutionalMemoryJson: String?,
        val notes: String?
    )

    fun encode(snapshot: ContinuitySnapshot): String {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("snapshotId", snapshot.snapshotId)
            put("aiId", snapshot.aiId)
            put("createdAtMs", snapshot.createdAtMs)
            put("ragChunksJson", snapshot.ragChunksJson)
            put("emotionalHistoryJson", snapshot.emotionalHistoryJson)
            put("personalityTraitsJson", snapshot.personalityTraitsJson)
            put("consolidationStatsJson", snapshot.consolidationStatsJson)
            put("constitutionalMemoryJson", snapshot.constitutionalMemoryJson)
            put("notes", snapshot.notes)
        }.toString()
    }

    fun decode(data: String): ContinuitySnapshot? {
        return try {
            val obj = JSONObject(data)
            ContinuitySnapshot(
                snapshotId = obj.optString("snapshotId", ""),
                aiId = obj.optString("aiId", ""),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                ragChunksJson = obj.optNullableString("ragChunksJson"),
                emotionalHistoryJson = obj.optNullableString("emotionalHistoryJson"),
                personalityTraitsJson = obj.optNullableString("personalityTraitsJson"),
                consolidationStatsJson = obj.optNullableString("consolidationStatsJson"),
                constitutionalMemoryJson = obj.optNullableString("constitutionalMemoryJson"),
                notes = obj.optNullableString("notes")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun sanitizeIdentifier(input: String?, fallback: String): String {
        val cleaned = (input ?: "")
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(MAX_IDENTIFIER_LENGTH)
        return if (cleaned.isNotEmpty()) cleaned else fallback
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return getString(key)
    }

    private const val MAX_IDENTIFIER_LENGTH = 64
}
