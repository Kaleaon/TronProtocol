package com.tronprotocol.app.persona

import android.util.Log

/**
 * Multi-tier emotional state tracking for persona-aware generation.
 *
 * Ported from ToolNeuron's EmotionalStateTracker. Implements a 4-tier scoring
 * system with weighted fusion to determine the current emotional regime:
 *
 * | Tier | Method                    | Latency  | Weight |
 * |------|---------------------------|----------|--------|
 * | 0    | Residual stream probing   | ~9μs     | 50%    |
 * | 1    | Keyword sentiment         | <1ms     | 10%    |
 * | 2    | Embedding similarity      | ~50ms    | 25%    |
 * | 3    | LLM mood tagging          | ~2-5s    | 15%    |
 *
 * Tier 0 comes from native backend signals (GGUF-specific).
 * Tier 1 uses regex-based keyword detection.
 * Tier 2 requires an embedding engine.
 * Tier 3 is only run during idle periods.
 *
 * Integrates with TronProtocol's [com.tronprotocol.app.affect.AffectEngine]
 * and [com.tronprotocol.app.emotion.EmotionalStateManager].
 */
class EmotionalStateTracker {

    /** Current regime determined by multi-tier fusion. */
    var currentRegime: EmotionRegime = EmotionRegime.NEUTRAL
        private set

    /** Raw scores from each tier (0.0 = very negative, 1.0 = very positive). */
    private val tierScores = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)

    /** Smoothed emotion score (EMA). */
    private var smoothedScore = 0.5f

    /** History of regime transitions for feedback correction. */
    private val regimeHistory = mutableListOf<RegimeTransition>()

    /** Callback for regime changes. */
    var onRegimeChange: ((EmotionRegime) -> Unit)? = null

    /**
     * Update Tier 0: residual stream probing signal from native backend.
     * Expected range: 0.0 (negative) to 1.0 (positive).
     */
    fun updateTier0(score: Float) {
        tierScores[0] = score.coerceIn(0f, 1f)
        recompute()
    }

    /**
     * Update Tier 1: keyword sentiment from text.
     * Performs regex-based keyword detection on the given text.
     */
    fun updateTier1(text: String) {
        tierScores[1] = computeKeywordSentiment(text)
        recompute()
    }

    /**
     * Update Tier 2: embedding similarity score.
     * Expected range: 0.0 (negative) to 1.0 (positive).
     */
    fun updateTier2(score: Float) {
        tierScores[2] = score.coerceIn(0f, 1f)
        recompute()
    }

    /**
     * Update Tier 3: LLM mood tagging result.
     * Expected range: 0.0 (negative) to 1.0 (positive).
     */
    fun updateTier3(score: Float) {
        tierScores[3] = score.coerceIn(0f, 1f)
        recompute()
    }

    /**
     * Process a conversation turn (both user and assistant text).
     * Convenience method that updates Tier 1 from text.
     */
    fun processTurn(userText: String, assistantText: String) {
        // Tier 1: keyword analysis on both texts
        val userScore = computeKeywordSentiment(userText)
        val assistantScore = computeKeywordSentiment(assistantText)
        tierScores[1] = (userScore * 0.6f + assistantScore * 0.4f)
        recompute()
    }

    /**
     * Get the current fused emotion score.
     */
    fun getFusedScore(): Float = smoothedScore

    /**
     * Get individual tier scores for debugging/visualization.
     */
    fun getTierScores(): FloatArray = tierScores.copyOf()

    /**
     * Get regime transition history.
     */
    fun getRegimeHistory(): List<RegimeTransition> = regimeHistory.toList()

    /**
     * Provide feedback on regime accuracy for correction.
     */
    fun feedbackCorrection(actualRegime: EmotionRegime) {
        if (actualRegime != currentRegime) {
            Log.d(TAG, "Feedback correction: $currentRegime -> $actualRegime")
            // Adjust tier weights based on which tiers were most wrong
            // This is simplified; full impl uses gradient-free optimization
            setRegime(actualRegime)
        }
    }

    /**
     * Reset to neutral state.
     */
    fun reset() {
        tierScores.fill(0.5f)
        smoothedScore = 0.5f
        currentRegime = EmotionRegime.NEUTRAL
        regimeHistory.clear()
    }

    // ---- Internal ----

    private fun recompute() {
        // Weighted fusion: 50% tier 0, 10% tier 1, 25% tier 2, 15% tier 3
        val rawScore = tierScores[0] * TIER_WEIGHTS[0] +
                tierScores[1] * TIER_WEIGHTS[1] +
                tierScores[2] * TIER_WEIGHTS[2] +
                tierScores[3] * TIER_WEIGHTS[3]

        // EMA smoothing
        smoothedScore = EMA_ALPHA * rawScore + (1 - EMA_ALPHA) * smoothedScore

        // Map score to regime
        val newRegime = scoreToRegime(smoothedScore)
        setRegime(newRegime)
    }

    private fun setRegime(newRegime: EmotionRegime) {
        if (newRegime != currentRegime) {
            val transition = RegimeTransition(
                from = currentRegime,
                to = newRegime,
                score = smoothedScore,
                timestamp = System.currentTimeMillis()
            )
            regimeHistory.add(transition)

            // Keep history bounded
            if (regimeHistory.size > MAX_HISTORY) {
                regimeHistory.removeAt(0)
            }

            currentRegime = newRegime
            Log.d(TAG, "Regime transition: ${transition.from} -> ${transition.to} (score=${"%.3f".format(smoothedScore)})")
            onRegimeChange?.invoke(newRegime)
        }
    }

    private fun scoreToRegime(score: Float): EmotionRegime {
        return when {
            score < 0.2f -> EmotionRegime.TENSE
            score < 0.35f -> EmotionRegime.COOLING
            score < 0.45f -> EmotionRegime.VULNERABLE
            score < 0.55f -> EmotionRegime.NEUTRAL
            score < 0.65f -> EmotionRegime.WARMING
            score < 0.8f -> EmotionRegime.PLAYFUL
            else -> EmotionRegime.EXCITED
        }
    }

    private fun computeKeywordSentiment(text: String): Float {
        if (text.isBlank()) return 0.5f

        val lower = text.lowercase()
        var positiveCount = 0
        var negativeCount = 0

        for (word in POSITIVE_KEYWORDS) {
            if (lower.contains(word)) positiveCount++
        }
        for (word in NEGATIVE_KEYWORDS) {
            if (lower.contains(word)) negativeCount++
        }

        val total = positiveCount + negativeCount
        if (total == 0) return 0.5f

        return (positiveCount.toFloat() / total).coerceIn(0f, 1f)
    }

    data class RegimeTransition(
        val from: EmotionRegime,
        val to: EmotionRegime,
        val score: Float,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "EmotionalStateTracker"
        private const val EMA_ALPHA = 0.3f
        private const val MAX_HISTORY = 50

        private val TIER_WEIGHTS = floatArrayOf(0.50f, 0.10f, 0.25f, 0.15f)

        private val POSITIVE_KEYWORDS = listOf(
            "happy", "great", "love", "wonderful", "amazing", "excellent",
            "good", "nice", "thanks", "thank", "awesome", "fantastic",
            "beautiful", "joy", "excited", "glad", "pleased", "fun",
            "helpful", "appreciate", "perfect", "brilliant", "wow"
        )

        private val NEGATIVE_KEYWORDS = listOf(
            "sad", "angry", "hate", "terrible", "awful", "horrible",
            "bad", "wrong", "fail", "error", "frustrat", "annoy",
            "disappoint", "upset", "worry", "stress", "pain", "hurt",
            "confus", "difficult", "problem", "issue", "broken"
        )
    }
}
