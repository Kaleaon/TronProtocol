package com.tronprotocol.app.affect

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tamper-proof, append-only record of every affect state transition.
 *
 * Serves as:
 * 1. **Authenticity verification** — trace any expression backward to the
 *    upstream affect state and inputs that caused it.
 * 2. **Self-knowledge** — the AI can review its own emotional history.
 * 3. **Trust** — a bonded partner can verify that expressions are consistent
 *    and non-performative.
 * 4. **Ethical safeguard** — part of the immutable kernel that the
 *    self-modification system cannot alter.
 *
 * Storage: encrypted via AES-256-GCM (existing [SecureStorage] / [EncryptionManager]).
 * The log is append-only. The self-modification system has an explicit exclusion —
 * it **cannot** modify, delete, or alter the AffectLog schema or storage mechanism.
 *
 * Entries are linked by a SHA-256 hash chain. Tampering with any past entry
 * invalidates all subsequent hashes.
 */
class ImmutableAffectLog(context: Context) {

    private val storage = SecureStorage(context)

    /** In-memory ring buffer of recent entries for fast access. */
    private val recentEntries = mutableListOf<AffectLogEntry>()

    /** SHA-256 hash of the most recent entry (chain head). */
    private var lastHash: String = GENESIS_HASH

    /** Total number of entries ever written. */
    private var entryCount: Long = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        loadChainHead()
    }

    // ---- Append -------------------------------------------------------------

    /**
     * Append a new entry to the log. This is the only write operation.
     *
     * @param affectState Current affect vector snapshot.
     * @param inputSources Labels of the inputs that produced this state.
     * @param expressionCommands Intentional expression outputs.
     * @param noiseResult Motor noise calculation result.
     */
    fun append(
        affectState: AffectState,
        inputSources: List<String>,
        expressionCommands: Map<String, String>,
        noiseResult: MotorNoise.NoiseResult
    ) {
        val timestamp = isoFormat.format(Date())
        val vectorMap = affectState.toMap()

        // Build content string for hashing (deterministic ordering).
        val contentForHash = buildContentString(
            timestamp, vectorMap, inputSources,
            expressionCommands, noiseResult.overallNoiseLevel,
            noiseResult.distributionMap()
        )
        val hash = sha256("$lastHash|$contentForHash")

        val entry = AffectLogEntry(
            timestamp = timestamp,
            affectVector = vectorMap,
            inputSources = inputSources,
            expressionCommands = expressionCommands,
            motorNoiseLevel = noiseResult.overallNoiseLevel,
            noiseDistribution = noiseResult.distributionMap(),
            hash = hash,
            immutable = true
        )

        // Persist the entry.
        persistEntry(entry)

        // Update chain state.
        lastHash = hash
        entryCount++
        saveChainHead()

        // Keep recent entries in memory.
        recentEntries.add(entry)
        while (recentEntries.size > MAX_RECENT_ENTRIES) {
            recentEntries.removeAt(0)
        }
    }

    // ---- Read ---------------------------------------------------------------

    /**
     * Get the [n] most recent log entries from the in-memory buffer.
     */
    fun getRecentEntries(n: Int = MAX_RECENT_ENTRIES): List<AffectLogEntry> {
        val count = n.coerceAtMost(recentEntries.size)
        return recentEntries.subList(recentEntries.size - count, recentEntries.size).toList()
    }

    /**
     * Get the current chain head hash.
     */
    fun getChainHeadHash(): String = lastHash

    /**
     * Total entries ever written.
     */
    fun getEntryCount(): Long = entryCount

    /**
     * Verify the integrity of recent entries by recomputing the hash chain.
     *
     * @return true if all recent entries have valid hash linkage.
     */
    fun verifyRecentIntegrity(): Boolean {
        if (recentEntries.isEmpty()) return true

        // We can only verify within the in-memory buffer.
        for (i in 1 until recentEntries.size) {
            val prev = recentEntries[i - 1]
            val curr = recentEntries[i]

            val expectedContent = buildContentString(
                curr.timestamp, curr.affectVector, curr.inputSources,
                curr.expressionCommands, curr.motorNoiseLevel,
                curr.noiseDistribution
            )
            val expectedHash = sha256("${prev.hash}|$expectedContent")

            if (curr.hash != expectedHash) {
                Log.e(TAG, "Integrity violation at entry $i: expected=$expectedHash, actual=${curr.hash}")
                return false
            }
        }

        return true
    }

    // ---- Stats --------------------------------------------------------------

    fun getStats(): Map<String, Any> = mapOf(
        "entry_count" to entryCount,
        "recent_buffer_size" to recentEntries.size,
        "chain_head_hash" to lastHash,
        "integrity_valid" to verifyRecentIntegrity()
    )

    // ---- Internal -----------------------------------------------------------

    private fun buildContentString(
        timestamp: String,
        vector: Map<String, Float>,
        sources: List<String>,
        commands: Map<String, String>,
        noiseLevel: Float,
        noiseDist: Map<String, Float>
    ): String {
        val sb = StringBuilder()
        sb.append(timestamp).append('|')

        // Deterministic ordering of affect vector.
        for (dim in AffectDimension.entries) {
            sb.append(dim.key).append('=')
            sb.append("%.6f".format(vector[dim.key] ?: 0.0f)).append(',')
        }
        sb.append('|')

        // Sources sorted.
        sb.append(sources.sorted().joinToString(",")).append('|')

        // Commands sorted by key.
        for ((k, v) in commands.toSortedMap()) {
            sb.append(k).append('=').append(v).append(',')
        }
        sb.append('|')

        sb.append("%.6f".format(noiseLevel)).append('|')

        for ((k, v) in noiseDist.toSortedMap()) {
            sb.append(k).append('=').append("%.6f".format(v)).append(',')
        }

        return sb.toString()
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Persist a single entry. Each entry is stored as a separate key to keep
     * append-only semantics — previous entries are never overwritten.
     */
    private fun persistEntry(entry: AffectLogEntry) {
        try {
            // Store individual entry.
            val key = "${ENTRY_KEY_PREFIX}${entryCount}"
            storage.store(key, entry.toJson().toString())

            // Also maintain a rolling index of the last N entries for fast reload.
            val indexKey = RECENT_INDEX_KEY
            val indexJson = try {
                val existing = storage.retrieve(indexKey)
                if (existing != null) JSONArray(existing) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }

            indexJson.put(entry.toJson())

            // Trim to max recent.
            while (indexJson.length() > MAX_RECENT_ENTRIES) {
                indexJson.remove(0)
            }

            storage.store(indexKey, indexJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist affect log entry", e)
        }
    }

    private fun saveChainHead() {
        try {
            val json = JSONObject().apply {
                put("last_hash", lastHash)
                put("entry_count", entryCount)
            }
            storage.store(CHAIN_HEAD_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chain head", e)
        }
    }

    private fun loadChainHead() {
        try {
            // Restore chain state.
            val headData = storage.retrieve(CHAIN_HEAD_KEY)
            if (headData != null) {
                val json = JSONObject(headData)
                lastHash = json.optString("last_hash", GENESIS_HASH)
                entryCount = json.optLong("entry_count", 0)
            }

            // Restore recent entries buffer.
            val indexData = storage.retrieve(RECENT_INDEX_KEY)
            if (indexData != null) {
                val indexJson = JSONArray(indexData)
                for (i in 0 until indexJson.length()) {
                    try {
                        val entry = AffectLogEntry.fromJson(indexJson.getJSONObject(i))
                        recentEntries.add(entry)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt log entry at index $i", e)
                    }
                }
            }

            Log.d(TAG, "Loaded affect log: $entryCount entries, ${recentEntries.size} recent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load affect log state", e)
        }
    }

    companion object {
        private const val TAG = "ImmutableAffectLog"

        /** Maximum entries kept in the in-memory recent buffer. */
        private const val MAX_RECENT_ENTRIES = 100

        /** Genesis hash for the first entry in the chain. */
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        /** SecureStorage key prefix for individual entries. */
        private const val ENTRY_KEY_PREFIX = "affect_log_entry_"

        /** SecureStorage key for the recent entries index. */
        private const val RECENT_INDEX_KEY = "affect_log_recent_index"

        /** SecureStorage key for chain head metadata. */
        private const val CHAIN_HEAD_KEY = "affect_log_chain_head"
    }
}
