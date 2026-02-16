package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Base64
import com.tronprotocol.app.rag.ContinuitySnapshotCodec
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray

/**
 * Continuity bridge for preserving/restoring AI memory + personality state across failures/upgrades.
 *
 * Inspired by OpenClaw's durable memory approach with explicit snapshot artifacts.
 *
 * Commands:
 * - snapshot|<ai_id>|<snapshot_id>|<optional notes>
 * - restore|<snapshot_id>|<target_ai_id>
 * - export|<snapshot_id>
 * - import|<base64_payload>|<target_ai_id>
 * - list
 * - inspect|<snapshot_id>
 * - provider_links
 */
class ContinuityBridgePlugin : Plugin {
    override val id: String = "continuity_bridge"
    override val name: String = "Continuity Bridge"
    override val description: String =
        "Create/restore/export/import continuity snapshots. Commands: snapshot, restore, export, import, list, inspect, provider_links"
    override var isEnabled: Boolean = true

    private lateinit var storage: SecureStorage

    override fun initialize(context: Context) {
        storage = SecureStorage(context)
    }

    override fun destroy() {
        // No-op
    }

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("|")
            when (parts.firstOrNull()?.trim()?.lowercase()) {
                "snapshot" -> createSnapshot(parts, start)
                "restore" -> restoreSnapshot(parts, start)
                "export" -> exportSnapshot(parts, start)
                "import" -> importSnapshot(parts, start)
                "list" -> listSnapshots(start)
                "inspect" -> inspectSnapshot(parts, start)
                "provider_links" -> providerLinks(start)
                else -> PluginResult.error("Unknown command. Use snapshot, restore, export, import, list, inspect, provider_links", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Continuity bridge failed: ${e.message}", elapsed(start))
        }
    }

    private fun createSnapshot(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: snapshot|<ai_id>|<snapshot_id>|<optional notes>", elapsed(start))
        }

        val aiId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[1], "default")
        val snapshotId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[2], "snapshot_${System.currentTimeMillis()}")
        val notes = parts.getOrNull(3)?.trim()?.take(300)

        val snapshot = ContinuitySnapshotCodec.ContinuitySnapshot(
            snapshotId = snapshotId,
            aiId = aiId,
            createdAtMs = System.currentTimeMillis(),
            ragChunksJson = storage.retrieve("rag_chunks_$aiId"),
            emotionalHistoryJson = storage.retrieve(KEY_EMOTIONAL_HISTORY),
            personalityTraitsJson = storage.retrieve(KEY_PERSONALITY_TRAITS),
            constitutionalMemoryJson = storage.retrieve(KEY_CONSTITUTIONAL_MEMORY),
            notes = notes
        )

        storage.store(snapshotStorageKey(snapshotId), ContinuitySnapshotCodec.encode(snapshot))
        persistSnapshotIndex(addSnapshotId(snapshotId))

        return PluginResult.success(
            "Snapshot saved: $snapshotId (ai=$aiId, hasRag=${snapshot.ragChunksJson != null})",
            elapsed(start)
        )
    }

    private fun restoreSnapshot(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: restore|<snapshot_id>|<target_ai_id>", elapsed(start))
        }

        val snapshotId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[1], "")
        if (snapshotId.isBlank()) {
            return PluginResult.error("Invalid snapshot id", elapsed(start))
        }

        val targetAiId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[2], "default")
        val encoded = storage.retrieve(snapshotStorageKey(snapshotId))
            ?: return PluginResult.error("Snapshot not found: $snapshotId", elapsed(start))
        val snapshot = ContinuitySnapshotCodec.decode(encoded)
            ?: return PluginResult.error("Snapshot is corrupted: $snapshotId", elapsed(start))

        snapshot.ragChunksJson?.let { storage.store("rag_chunks_$targetAiId", it) }
        snapshot.emotionalHistoryJson?.let { storage.store(KEY_EMOTIONAL_HISTORY, it) }
        snapshot.personalityTraitsJson?.let { storage.store(KEY_PERSONALITY_TRAITS, it) }
        snapshot.constitutionalMemoryJson?.let { storage.store(KEY_CONSTITUTIONAL_MEMORY, it) }

        return PluginResult.success(
            "Snapshot restored: $snapshotId -> ai=$targetAiId",
            elapsed(start)
        )
    }

    private fun listSnapshots(start: Long): PluginResult {
        val ids = getSnapshotIds()
        if (ids.isEmpty()) {
            return PluginResult.success("No continuity snapshots available", elapsed(start))
        }
        val payload = buildString {
            append("Continuity snapshots:\n")
            ids.forEach { append("- ").append(it).append('\n') }
        }
        return PluginResult.success(payload.trimEnd(), elapsed(start))
    }

    private fun inspectSnapshot(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: inspect|<snapshot_id>", elapsed(start))
        }

        val snapshotId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[1], "")
        if (snapshotId.isBlank()) {
            return PluginResult.error("Invalid snapshot id", elapsed(start))
        }

        val encoded = storage.retrieve(snapshotStorageKey(snapshotId))
            ?: return PluginResult.error("Snapshot not found: $snapshotId", elapsed(start))
        val snapshot = ContinuitySnapshotCodec.decode(encoded)
            ?: return PluginResult.error("Snapshot is corrupted: $snapshotId", elapsed(start))

        return PluginResult.success(
            "Snapshot $snapshotId\naiId=${snapshot.aiId}\ncreatedAtMs=${snapshot.createdAtMs}\n" +
                "hasRag=${snapshot.ragChunksJson != null}\nhasEmotion=${snapshot.emotionalHistoryJson != null}\n" +
                "hasTraits=${snapshot.personalityTraitsJson != null}\nhasConstitution=${snapshot.constitutionalMemoryJson != null}",
            elapsed(start)
        )
    }

    private fun exportSnapshot(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: export|<snapshot_id>", elapsed(start))
        }
        val snapshotId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[1], "")
        if (snapshotId.isBlank()) {
            return PluginResult.error("Invalid snapshot id", elapsed(start))
        }
        val encoded = storage.retrieve(snapshotStorageKey(snapshotId))
            ?: return PluginResult.error("Snapshot not found: $snapshotId", elapsed(start))
        val payload = Base64.encodeToString(encoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return PluginResult.success(
            "EXPORT_PAYLOAD_BASE64:$payload",
            elapsed(start)
        )
    }

    private fun importSnapshot(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: import|<base64_payload>|<target_ai_id>", elapsed(start))
        }
        val payload = parts[1].trim()
        if (payload.isBlank()) {
            return PluginResult.error("Payload cannot be blank", elapsed(start))
        }
        val targetAiId = ContinuitySnapshotCodec.sanitizeIdentifier(parts[2], "default")
        val decodedJson = try {
            String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return PluginResult.error("Invalid base64 payload", elapsed(start))
        }
        val snapshot = ContinuitySnapshotCodec.decode(decodedJson)
            ?: return PluginResult.error("Payload does not contain a valid continuity snapshot", elapsed(start))

        val importedSnapshotId = buildString {
            append("imported_")
            append(ContinuitySnapshotCodec.sanitizeIdentifier(snapshot.snapshotId, "snapshot"))
            append("_")
            append(System.currentTimeMillis())
        }
        storage.store(snapshotStorageKey(importedSnapshotId), decodedJson)
        persistSnapshotIndex(addSnapshotId(importedSnapshotId))

        snapshot.ragChunksJson?.let { storage.store("rag_chunks_$targetAiId", it) }
        snapshot.emotionalHistoryJson?.let { storage.store(KEY_EMOTIONAL_HISTORY, it) }
        snapshot.personalityTraitsJson?.let { storage.store(KEY_PERSONALITY_TRAITS, it) }
        snapshot.constitutionalMemoryJson?.let { storage.store(KEY_CONSTITUTIONAL_MEMORY, it) }

        return PluginResult.success(
            "Imported snapshot as $importedSnapshotId and restored to ai=$targetAiId",
            elapsed(start)
        )
    }

    private fun providerLinks(start: Long): PluginResult {
        val links = buildString {
            append("Continuity snapshot providers:\n")
            append("- Google Drive: https://drive.google.com/\n")
            append("- Dropbox: https://www.dropbox.com/\n")
            append("- OneDrive: https://onedrive.live.com/\n")
            append("- GitHub Gist/Repo: https://github.com/\n")
            append("Use export|<snapshot_id> to produce payload, then store externally.")
        }
        return PluginResult.success(links, elapsed(start))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun snapshotStorageKey(snapshotId: String): String = "$KEY_SNAPSHOT_PREFIX$snapshotId"

    private fun getSnapshotIds(): List<String> {
        val encoded = storage.retrieve(KEY_SNAPSHOT_INDEX) ?: return emptyList()
        return try {
            val arr = JSONArray(encoded)
            (0 until arr.length()).mapNotNull { index ->
                arr.optString(index).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun addSnapshotId(snapshotId: String): List<String> {
        val ids = getSnapshotIds().toMutableList()
        if (!ids.contains(snapshotId)) {
            ids.add(snapshotId)
        }
        return ids
    }

    private fun persistSnapshotIndex(ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        storage.store(KEY_SNAPSHOT_INDEX, arr.toString())
    }

    companion object {
        private const val KEY_EMOTIONAL_HISTORY = "emotional_history"
        private const val KEY_PERSONALITY_TRAITS = "personality_traits"
        private const val KEY_CONSTITUTIONAL_MEMORY = "constitutional_memory"
        private const val KEY_SNAPSHOT_INDEX = "continuity_snapshot_index"
        private const val KEY_SNAPSHOT_PREFIX = "continuity_snapshot_"
    }
}
