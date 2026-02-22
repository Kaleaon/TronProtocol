package com.tronprotocol.app.drift

import org.json.JSONObject

/**
 * A drift measurement comparing recalled vs ground-truth memory.
 *
 * Drift score = 1.0 - cosine_similarity(recalled_embedding, ground_truth_embedding).
 * Higher drift = greater divergence between what was remembered and what actually happened.
 *
 * Drift scores above [ALERT_THRESHOLD] are flagged for partner review.
 */
data class DriftScore(
    val entryId: String,
    val recalledContent: String,
    val groundTruthHash: String,
    val cosineSimilarity: Float,
    val driftMagnitude: Float = 1.0f - cosineSimilarity,
    val timestamp: Long = System.currentTimeMillis(),
    val flaggedForReview: Boolean = driftMagnitude > ALERT_THRESHOLD
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("entry_id", entryId)
        put("cosine_similarity", cosineSimilarity.toDouble())
        put("drift_magnitude", driftMagnitude.toDouble())
        put("timestamp", timestamp)
        put("flagged_for_review", flaggedForReview)
        put("ground_truth_hash", groundTruthHash)
    }

    companion object {
        /** Cosine similarity threshold below which drift is flagged. */
        const val ALERT_THRESHOLD = 0.15f // 1.0 - 0.85 similarity
    }
}
