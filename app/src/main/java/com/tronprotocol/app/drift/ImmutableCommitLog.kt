package com.tronprotocol.app.drift

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Append-only immutable commit log with SHA-256 hash chain.
 *
 * All raw interaction records are written here as ground truth against
 * which drift is measured. Each entry's hash incorporates the previous
 * entry's hash, forming a tamper-evident chain.
 *
 * This log syncs to Google Drive backup and cannot be modified once written.
 */
class ImmutableCommitLog(context: Context) {

    private val storage = SecureStorage(context)
    private val entries = mutableListOf<LogEntry>()
    private var lastHash: String = GENESIS_HASH

    init {
        loadLog()
    }

    /**
     * Append a new record to the immutable log.
     *
     * @param content The raw interaction content
     * @param entryType Type of record (e.g., "interaction", "memory_write", "session_close")
     * @return The hash of the new entry
     */
    fun append(content: String, entryType: String): String {
        val entry = LogEntry(
            index = entries.size,
            content = content,
            entryType = entryType,
            timestamp = System.currentTimeMillis(),
            previousHash = lastHash,
            contentHash = computeContentHash(content)
        )
        entry.chainHash = computeChainHash(entry)
        entries.add(entry)
        lastHash = entry.chainHash
        persistLog()
        return entry.chainHash
    }

    /**
     * Verify the integrity of the entire hash chain.
     * @return true if all entries are valid, false if tampering is detected
     */
    fun verifyChain(): Boolean {
        var expectedPreviousHash = GENESIS_HASH
        for (entry in entries) {
            if (entry.previousHash != expectedPreviousHash) {
                Log.e(TAG, "Chain broken at index ${entry.index}: " +
                        "expected=$expectedPreviousHash, found=${entry.previousHash}")
                return false
            }
            val recomputedHash = computeChainHash(entry)
            if (entry.chainHash != recomputedHash) {
                Log.e(TAG, "Hash mismatch at index ${entry.index}")
                return false
            }
            expectedPreviousHash = entry.chainHash
        }
        return true
    }

    /** Get the content hash for a specific entry by index. */
    fun getContentHash(index: Int): String? =
        entries.getOrNull(index)?.contentHash

    /** Get content by index for drift comparison. */
    fun getContent(index: Int): String? =
        entries.getOrNull(index)?.content

    /** Total entries in the log. */
    fun size(): Int = entries.size

    /** Get the hash of the latest entry (chain tip). */
    fun getLatestHash(): String = lastHash

    /** Export the full log for cloud sync. */
    fun exportForSync(): JSONArray {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        return array
    }

    private fun computeContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun computeChainHash(entry: LogEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entry.previousHash.toByteArray(Charsets.UTF_8))
        digest.update(entry.content.toByteArray(Charsets.UTF_8))
        digest.update(entry.entryType.toByteArray(Charsets.UTF_8))
        digest.update(entry.timestamp.toString().toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun persistLog() {
        try {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            storage.store(PERSIST_KEY, array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist commit log", e)
        }
    }

    private fun loadLog() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val array = JSONArray(data)
            for (i in 0 until array.length()) {
                val entry = LogEntry.fromJson(array.getJSONObject(i))
                entries.add(entry)
            }
            if (entries.isNotEmpty()) {
                lastHash = entries.last().chainHash
            }
            Log.d(TAG, "Loaded ${entries.size} commit log entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load commit log", e)
        }
    }

    data class LogEntry(
        val index: Int,
        val content: String,
        val entryType: String,
        val timestamp: Long,
        val previousHash: String,
        val contentHash: String,
        var chainHash: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("index", index)
            put("content", content)
            put("entry_type", entryType)
            put("timestamp", timestamp)
            put("previous_hash", previousHash)
            put("content_hash", contentHash)
            put("chain_hash", chainHash)
        }

        companion object {
            fun fromJson(json: JSONObject): LogEntry = LogEntry(
                index = json.getInt("index"),
                content = json.getString("content"),
                entryType = json.getString("entry_type"),
                timestamp = json.getLong("timestamp"),
                previousHash = json.getString("previous_hash"),
                contentHash = json.getString("content_hash"),
                chainHash = json.getString("chain_hash")
            )
        }
    }

    companion object {
        private const val TAG = "ImmutableCommitLog"
        private const val PERSIST_KEY = "immutable_commit_log"
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
