package com.tronprotocol.app.rag

import org.json.JSONObject

/**
 * Encodes/decodes portable continuity snapshots so memories and traits can be restored
 * after crashes, reinstalls, or model upgrades.
 */
object ContinuitySnapshotCodec {
    private const val CURRENT_SCHEMA_VERSION = 2

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

    data class DecodeResult(
        val snapshot: ContinuitySnapshot,
        val schemaVersion: Int,
        val wasMigrated: Boolean,
        val migrationPath: String,
        val normalizedPayload: String
    )

    fun encode(snapshot: ContinuitySnapshot): String {
        return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
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
        return decodeWithMigration(data)?.snapshot
    }

    fun decodeWithMigration(data: String): DecodeResult? {
        return try {
            val rawObject = JSONObject(data)
            val sourceVersion = detectSchemaVersion(rawObject)
            val migratedObject = migrateToCurrentSchema(rawObject, sourceVersion) ?: return null
            val snapshot = parseSnapshot(migratedObject) ?: return null

            DecodeResult(
                snapshot = snapshot,
                schemaVersion = CURRENT_SCHEMA_VERSION,
                wasMigrated = sourceVersion != CURRENT_SCHEMA_VERSION,
                migrationPath = "v$sourceVersion->v$CURRENT_SCHEMA_VERSION",
                normalizedPayload = encode(snapshot)
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

    private fun detectSchemaVersion(obj: JSONObject): Int {
        val explicitVersion = obj.optInt("schemaVersion", -1)
        if (explicitVersion > 0) return explicitVersion
        return 1
    }

    private fun migrateToCurrentSchema(obj: JSONObject, sourceVersion: Int): JSONObject? {
        if (sourceVersion <= 0 || sourceVersion > CURRENT_SCHEMA_VERSION) {
            return null
        }

        var migrated = JSONObject(obj.toString())
        var currentVersion = sourceVersion

        while (currentVersion < CURRENT_SCHEMA_VERSION) {
            migrated = when (currentVersion) {
                1 -> migrateV1ToV2(migrated)
                else -> return null
            }
            currentVersion += 1
        }

        migrated.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        return migrated
    }

    private fun migrateV1ToV2(v1: JSONObject): JSONObject {
        val migrated = JSONObject(v1.toString())
        migrated.put("schemaVersion", 2)
        if (migrated.has("snapshotNotes") && !migrated.has("notes")) {
            migrated.put("notes", migrated.optString("snapshotNotes"))
        }
        if (!migrated.has("notes")) {
            migrated.put("notes", JSONObject.NULL)
        }
        return migrated
    }

    private fun parseSnapshot(obj: JSONObject): ContinuitySnapshot? {
        val snapshotId = sanitizeIdentifier(obj.optString("snapshotId", ""), "")
        val aiId = sanitizeIdentifier(obj.optString("aiId", ""), "")
        val createdAtMs = obj.optLong("createdAtMs", 0L)

        if (snapshotId.isBlank() || aiId.isBlank() || createdAtMs <= 0L) {
            return null
        }

        return ContinuitySnapshot(
            snapshotId = snapshotId,
            aiId = aiId,
            createdAtMs = createdAtMs,
            ragChunksJson = obj.optNullableString("ragChunksJson"),
            emotionalHistoryJson = obj.optNullableString("emotionalHistoryJson"),
            personalityTraitsJson = obj.optNullableString("personalityTraitsJson"),
            consolidationStatsJson = obj.optNullableString("consolidationStatsJson"),
            constitutionalMemoryJson = obj.optNullableString("constitutionalMemoryJson"),
            notes = obj.optNullableString("notes")
        )
    }

    private const val MAX_IDENTIFIER_LENGTH = 64
}
