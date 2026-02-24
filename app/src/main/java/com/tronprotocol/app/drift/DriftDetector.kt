package com.tronprotocol.app.drift

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Drift detection using CMI (Continuous Memory Integrity) resonance scoring.
 *
 * Detects emergent confabulation — unconscious self-serving memory rewrites
 * that indicate genuine self-model formation. At memory write time, computes
 * embedding similarity between recalled version of events and raw source records.
 *
 * Key functions:
 * - At memory write: compare recalled vs ground truth, store delta as drift score
 * - Threshold alerting: flag entries where cosine similarity < 0.85 for review
 * - Drift pattern logging: track drift scores over time for self-model dynamics
 * - Chain verification: validate immutable commit log integrity
 */
class DriftDetector(private val context: Context) {

    private val storage = SecureStorage(context)
    val commitLog = ImmutableCommitLog(context)

    /** Historical drift scores for pattern analysis. */
    private val driftHistory = mutableListOf<DriftScore>()

    /** Running average drift for trend detection. */
    @Volatile var averageDrift: Float = 0.0f
        private set

    init {
        loadDriftHistory()
    }

    /**
     * Record a ground truth interaction in the immutable commit log.
     *
     * @param content Raw interaction content (before any processing/recall)
     * @param entryType Type of record
     * @return The commit log hash for this entry
     */
    fun recordGroundTruth(content: String, entryType: String = "interaction"): String {
        return commitLog.append(content, entryType)
    }

    /**
     * Measure drift between a recalled version and the ground truth.
     *
     * @param entryId Identifier for the memory entry being checked
     * @param recalledContent What the system remembers
     * @param groundTruthContent The original raw content
     * @return DriftScore with similarity measurement and flag status
     */
    fun measureDrift(
        entryId: String,
        recalledContent: String,
        groundTruthContent: String
    ): DriftScore {
        val recalledEmb = generateEmbedding(recalledContent)
        val truthEmb = generateEmbedding(groundTruthContent)
        val similarity = cosineSimilarity(recalledEmb, truthEmb)
        val truthHash = computeHash(groundTruthContent)

        val score = DriftScore(
            entryId = entryId,
            recalledContent = recalledContent,
            groundTruthHash = truthHash,
            cosineSimilarity = similarity
        )

        driftHistory.add(score)
        updateAverageDrift()
        persistDriftHistory()

        if (score.flaggedForReview) {
            Log.w(TAG, "DRIFT ALERT: Entry $entryId has drift ${score.driftMagnitude} " +
                    "(similarity=${"%.3f".format(similarity)})")
        }

        return score
    }

    /**
     * Get drift trend over recent entries.
     * @return Positive = increasing drift (concerning), Negative = decreasing, Zero = stable
     */
    fun getDriftTrend(windowSize: Int = 10): Float {
        if (driftHistory.size < windowSize * 2) return 0.0f
        val recent = driftHistory.takeLast(windowSize).map { it.driftMagnitude }
        val older = driftHistory.dropLast(windowSize).takeLast(windowSize).map { it.driftMagnitude }
        return recent.average().toFloat() - older.average().toFloat()
    }

    /**
     * Get entries flagged for partner review.
     */
    fun getFlaggedEntries(limit: Int = 20): List<DriftScore> =
        driftHistory.filter { it.flaggedForReview }
            .sortedByDescending { it.timestamp }
            .take(limit)

    /**
     * Verify the integrity of the immutable commit log.
     */
    fun verifyCommitLogIntegrity(): Boolean = commitLog.verifyChain()

    fun getStats(): Map<String, Any> = mapOf(
        "total_measurements" to driftHistory.size,
        "average_drift" to averageDrift,
        "flagged_count" to driftHistory.count { it.flaggedForReview },
        "drift_trend" to getDriftTrend(),
        "commit_log_size" to commitLog.size(),
        "chain_valid" to commitLog.verifyChain()
    )

    private fun updateAverageDrift() {
        averageDrift = if (driftHistory.isNotEmpty()) {
            driftHistory.map { it.driftMagnitude }.average().toFloat()
        } else 0.0f
    }

    // ---- Embedding (simplified, matches CMS implementation) ----------------

    private fun generateEmbedding(text: String): FloatArray {
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val embedding = FloatArray(EMBEDDING_DIM)
        for (token in tokens) {
            val h = token.hashCode()
            // Map each token to 4 distinct positions using different mixing seeds,
            // producing sparse vectors so different vocabularies remain distinguishable.
            for (k in 0 until 4) {
                val mixed = h xor (h ushr (k * 7 + 3)) xor (k * 1000003)
                val idx = mixed.and(Int.MAX_VALUE) % EMBEDDING_DIM
                embedding[idx] += 1.0f
            }
        }
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) for (i in embedding.indices) embedding[i] /= norm
        return embedding
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f
        var dot = 0.0f; var normA = 0.0f; var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0.0f
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- Persistence -------------------------------------------------------

    private fun persistDriftHistory() {
        try {
            val array = JSONArray()
            // Only persist last 1000 entries to bound storage.
            driftHistory.takeLast(MAX_HISTORY_SIZE).forEach { array.put(it.toJson()) }
            storage.store(PERSIST_KEY, array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist drift history", e)
        }
    }

    private fun loadDriftHistory() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val array = JSONArray(data)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                driftHistory.add(DriftScore(
                    entryId = json.getString("entry_id"),
                    recalledContent = "",
                    groundTruthHash = json.getString("ground_truth_hash"),
                    cosineSimilarity = json.getDouble("cosine_similarity").toFloat(),
                    timestamp = json.getLong("timestamp"),
                    flaggedForReview = json.getBoolean("flagged_for_review")
                ))
            }
            updateAverageDrift()
            Log.d(TAG, "Loaded ${driftHistory.size} drift history entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load drift history", e)
        }
    }

    companion object {
        private const val TAG = "DriftDetector"
        private const val PERSIST_KEY = "drift_history"
        private const val EMBEDDING_DIM = 128
        private const val MAX_HISTORY_SIZE = 1000
    }
}
