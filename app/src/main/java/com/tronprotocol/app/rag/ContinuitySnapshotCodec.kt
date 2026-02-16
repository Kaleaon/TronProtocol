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
                ragChunksJson = obj.optString("ragChunksJson", null),
                emotionalHistoryJson = obj.optString("emotionalHistoryJson", null),
                personalityTraitsJson = obj.optString("personalityTraitsJson", null),
                constitutionalMemoryJson = obj.optString("constitutionalMemoryJson", null),
                notes = obj.optString("notes", null)
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

    private const val MAX_IDENTIFIER_LENGTH = 64
}
