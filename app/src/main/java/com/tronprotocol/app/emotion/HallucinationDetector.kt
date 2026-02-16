package com.tronprotocol.app.emotion

import android.content.Context
import android.util.Log
import com.tronprotocol.app.frontier.ConfidenceLevel
import com.tronprotocol.app.frontier.FrontierDynamicsManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalResult
import com.tronprotocol.app.rag.RetrievalStrategy
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Hallucination Detector
 *
 * Integrates state-of-the-art techniques from:
 * - SelfCheckGPT (arXiv:2303.08896) - Consistency-based detection
 * - awesome-hallucination-detection (EdinburghNLP) - Multiple detection methods
 *
 * Detection strategies:
 * 1. Self-Consistency: Multiple generations compared for agreement
 * 2. Retrieval-Augmented: Compare response against retrieved facts
 * 3. Uncertainty Estimation: Internal confidence scoring
 * 4. Claim Decomposition: Break response into verifiable claims
 * 5. Temporal Decay: Recent embarrassments increase caution
 */
class HallucinationDetector(
    private val context: Context,
    private val emotionalManager: EmotionalStateManager
) {

    var ragStore: RAGStore? = null

    /** Optional Frontier Dynamics manager for STLE-based uncertainty scoring. */
    var frontierDynamicsManager: FrontierDynamicsManager? = null

    /**
     * Comprehensive hallucination detection pipeline
     *
     * @param response The AI-generated response to check
     * @param alternativeResponses Multiple alternative generations for consistency check
     * @param prompt Original prompt for context
     * @return HallucinationResult with detection details
     */
    fun detectHallucination(
        response: String,
        alternativeResponses: List<String>?,
        prompt: String
    ): HallucinationResult {
        val result = HallucinationResult()
        result.response = response

        // Strategy 1: Self-Consistency Check (SelfCheckGPT)
        if (!alternativeResponses.isNullOrEmpty()) {
            val allResponses = mutableListOf<String>()
            allResponses.add(response)
            allResponses.addAll(alternativeResponses)

            val consistency = emotionalManager.checkConsistency(allResponses)

            result.consistencyScore = consistency.similarityScore
            result.isConsistent = consistency.isConsistent

            if (!consistency.isConsistent) {
                result.hallucinationType = HallucinationType.INCONSISTENT
                result.confidence = 0.9f // High confidence it's a hallucination
                Log.w(TAG, "Inconsistency detected: ${consistency.assessment}")
            }
        }

        // Strategy 2: Retrieval-Augmented Verification
        ragStore?.let { store ->
            try {
                val retrievedFacts = store.retrieve(
                    prompt, RetrievalStrategy.MEMRL, 5
                )

                if (retrievedFacts.isNotEmpty()) {
                    val factualSupport = calculateFactualSupport(response, retrievedFacts)
                    result.factualSupportScore = factualSupport

                    if (factualSupport < LOW_FACTUAL_SUPPORT_THRESHOLD) {
                        result.hallucinationType = HallucinationType.UNSUPPORTED
                        result.confidence = max(result.confidence, CONSISTENCY_THRESHOLD)
                        Log.w(TAG, "Low factual support: $factualSupport")
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "Error in RAG verification", e)
            }
        }

        // Strategy 3: Claim Decomposition & Verification
        val claims = decomposeClaims(response)
        result.claims = claims
        result.claimCount = claims.size

        if (claims.size > MAX_CLAIMS_FOR_OVERSPECIFIC && result.consistencyScore < LOW_CONSISTENCY_CLAIMS_THRESHOLD) {
            // Many specific claims with low consistency = likely hallucinating details
            result.hallucinationType = HallucinationType.OVERSPECIFIC
            result.confidence = max(result.confidence, 0.75f)
        }

        // Strategy 4: Uncertainty Patterns
        val uncertaintyScore = detectUncertaintyPatterns(response)
        result.uncertaintyScore = uncertaintyScore

        if (uncertaintyScore > HIGH_UNCERTAINTY_THRESHOLD) {
            // AI is uncertain but still generating - risky
            result.hallucinationType = HallucinationType.UNCERTAIN_GENERATION
            result.confidence = max(result.confidence, CONFIDENCE_THRESHOLD)
        }

        // Strategy 4b: Takens-inspired thread/basin separation
        // Facts should form thin verifiable "threads"; opinions should settle in broad basins.
        val takensSignals = calculateTakensThreadSignals(response, claims, uncertaintyScore)
        result.factThreadScore = takensSignals.factThreadScore
        result.opinionBasinScore = takensSignals.opinionBasinScore

        if (result.claimCount >= MIN_FACTUAL_CLAIMS_FOR_THREAD_CHECK &&
            result.factThreadScore < LOW_FACT_THREAD_THRESHOLD &&
            result.opinionBasinScore < LOW_OPINION_BASIN_THRESHOLD &&
            result.factualSupportScore < WEAK_FACTUAL_SUPPORT_THRESHOLD
        ) {
            result.hallucinationType = HallucinationType.UNSUPPORTED
            result.confidence = max(result.confidence, FACT_THREAD_CONFIDENCE_BOOST)
        }

        // Strategy 5: Emotional Bias (recent embarrassments)
        val emotionalBias = emotionalManager.getEmotionalBias()
        result.emotionalBias = emotionalBias

        // Strategy 6: Frontier Dynamics STLE accessibility check
        // Uses the Set Theoretic Learning Environment to score how
        // "in-distribution" the retrieved context is. Low mu_x means
        // the model is operating outside its training distribution.
        val fdm = frontierDynamicsManager
        val store = ragStore
        if (fdm != null && store != null) {
            try {
                val retrievedFacts = store.retrieve(
                    prompt, RetrievalStrategy.MEMRL, 5
                )
                if (retrievedFacts.isNotEmpty()) {
                    val aggregate = fdm.aggregateAccessibility(retrievedFacts)
                    result.frontierAccessibility = aggregate.meanMuX
                    result.frontierConfidence = aggregate.confidenceLevel

                    if (aggregate.confidenceLevel == ConfidenceLevel.VERY_LOW) {
                        result.hallucinationType = HallucinationType.FRONTIER_OOD
                        result.confidence = max(result.confidence, 0.7f)
                        Log.w(TAG, "STLE frontier OOD: mean mu_x=${aggregate.meanMuX}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in STLE frontier check", e)
            }
        }

        // Final determination
        result.isHallucination = determineHallucination(result)

        // Apply emotional learning if hallucination detected
        if (result.isHallucination) {
            val hallucinationContext = String.format(
                "Hallucination detected (%s): %s",
                result.hallucinationType,
                response.substring(0, min(50, response.length))
            )
            emotionalManager.applyEmbarrassment(hallucinationContext, result.confidence)
        }

        return result
    }

    /**
     * Calculate how well the response is supported by retrieved facts.
     *
     * Uses Set-based intersection (O(n+m)) instead of nested loops (O(n*m))
     * for efficient word overlap computation. Filters stop words and short
     * tokens to reduce noise.
     */
    private fun calculateFactualSupport(response: String, facts: List<RetrievalResult>): Float {
        if (facts.isEmpty()) return 0.0f

        val responseLower = response.lowercase()
        val responseWords = responseLower.split("\\s+".toRegex())
            .filter { it.length > MIN_WORD_LENGTH_FOR_OVERLAP }
            .toSet()

        if (responseWords.isEmpty()) return 0.0f

        var totalOverlap = 0

        for (fact in facts) {
            val factContent = fact.chunk.content.lowercase()
            val factWordSet = factContent.split("\\s+".toRegex())
                .filter { it.length > MIN_WORD_LENGTH_FOR_OVERLAP && it !in STOP_WORDS }
                .toSet()

            // Set intersection: O(min(n,m)) instead of O(n*m)
            val overlap = responseWords.intersect(factWordSet).size
            totalOverlap += overlap
        }

        return min(1.0f, totalOverlap / max(1, responseWords.size).toFloat())
    }

    /**
     * Decompose response into verifiable atomic claims
     * Based on HALoGEN and claim decomposition research
     */
    private fun decomposeClaims(response: String): List<String> {
        val claims = mutableListOf<String>()

        // Simple sentence-based decomposition
        val sentences = response.split("[.!?]+".toRegex())
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.length > 10) {
                claims.add(trimmed)
            }
        }

        return claims
    }

    /**
     * Detect uncertainty patterns in the response
     * Phrases like "I think", "maybe", "possibly" indicate uncertainty
     */
    private fun detectUncertaintyPatterns(response: String): Float {
        val uncertainPhrases = arrayOf(
            "i think", "maybe", "possibly", "might be", "could be",
            "probably", "perhaps", "i'm not sure", "uncertain",
            "not certain", "guess", "assume"
        )

        val responseLower = response.lowercase()
        var uncertaintyCount = 0

        for (phrase in uncertainPhrases) {
            if (responseLower.contains(phrase)) {
                uncertaintyCount++
            }
        }

        // Normalize by number of sentences
        val sentenceCount = response.split("[.!?]+".toRegex()).size
        return min(1.0f, uncertaintyCount / max(1, sentenceCount).toFloat())
    }

    private data class TakensSignals(
        val factThreadScore: Float,
        val opinionBasinScore: Float
    )

    /**
     * Takens-inspired language geometry approximation:
     * - Fact thread score: narrow, verifiable, low-ambiguity claims
     * - Opinion basin score: subjective/hedged language spread
     */
    private fun calculateTakensThreadSignals(
        response: String,
        claims: List<String>,
        uncertaintyScore: Float
    ): TakensSignals {
        if (claims.isEmpty()) {
            return TakensSignals(0.0f, uncertaintyScore)
        }

        val factualMarkers = arrayOf(
            "according to", "measured", "recorded", "reported", "verified",
            "km", "kg", "mhz", "ghz", "ms", "seconds", "minutes", "years", "%"
        )
        val opinionMarkers = arrayOf(
            "i think", "i feel", "in my view", "it seems", "arguably",
            "probably", "perhaps", "maybe", "prefer", "opinion"
        )

        var factLikeClaims = 0
        var opinionLikeClaims = 0
        var numericTokens = 0
        var totalTokens = 0

        claims.forEach { claim ->
            val lower = claim.lowercase()
            val tokens = lower.split("\\s+".toRegex()).filter { it.isNotBlank() }
            totalTokens += tokens.size

            val hasNumeric = tokens.any { token -> token.any { it.isDigit() } }
            if (hasNumeric) {
                numericTokens += tokens.count { token -> token.any { it.isDigit() } }
            }

            val hasFactualMarker = factualMarkers.any { lower.contains(it) } || hasNumeric
            val hasOpinionMarker = opinionMarkers.any { lower.contains(it) }

            if (hasFactualMarker) factLikeClaims++
            if (hasOpinionMarker) opinionLikeClaims++
        }

        val factClaimRatio = factLikeClaims / claims.size.toFloat()
        val opinionClaimRatio = opinionLikeClaims / claims.size.toFloat()
        val numericDensity = if (totalTokens > 0) numericTokens / totalTokens.toFloat() else 0.0f

        val factThreadScore = min(1.0f, factClaimRatio * 0.8f + numericDensity * 2.0f)
        val opinionBasinScore = min(1.0f, opinionClaimRatio * 0.7f + uncertaintyScore * 0.6f)

        return TakensSignals(factThreadScore, opinionBasinScore)
    }

    /**
     * Make final hallucination determination based on multiple signals.
     *
     * Uses a two-tier approach:
     * 1. High-confidence single signal: any signal above HIGH_CONFIDENCE_THRESHOLD
     * 2. Convergence of weak signals: 3+ weak signals from independent detectors
     */
    private fun determineHallucination(result: HallucinationResult): Boolean {
        // High confidence hallucination signals
        if (result.confidence > HIGH_CONFIDENCE_THRESHOLD) return true

        // Multiple weak signals from independent detectors
        var weakSignals = 0
        if (!result.isConsistent) weakSignals++
        if (result.factualSupportScore < WEAK_FACTUAL_SUPPORT_THRESHOLD) weakSignals++
        if (result.uncertaintyScore > WEAK_UNCERTAINTY_THRESHOLD) weakSignals++
        if (result.emotionalBias < WEAK_EMOTIONAL_BIAS_THRESHOLD) weakSignals++ // Recently embarrassed
        if (result.frontierAccessibility < WEAK_FRONTIER_THRESHOLD) weakSignals++ // STLE OOD
        if (result.factThreadScore < WEAK_FACT_THREAD_THRESHOLD &&
            result.opinionBasinScore < WEAK_OPINION_BASIN_THRESHOLD) weakSignals++

        if (weakSignals >= MIN_WEAK_SIGNALS_FOR_HALLUCINATION) return true

        // Conservative: only flag clear hallucinations
        return false
    }

    /**
     * Get recommendation for handling potential hallucination
     */
    fun getRecommendation(result: HallucinationResult): String {
        if (!result.isHallucination) {
            return "Response appears factual. Proceed with confidence."
        }

        return when (result.hallucinationType) {
            HallucinationType.INCONSISTENT ->
                "Multiple generations inconsistent. Regenerate or defer to uncertainty."
            HallucinationType.UNSUPPORTED ->
                "No factual support found. Retrieve more context or admit uncertainty."
            HallucinationType.OVERSPECIFIC ->
                "Too many specific claims without support. Simplify or verify claims."
            HallucinationType.UNCERTAIN_GENERATION ->
                "AI is uncertain. Better to say 'I don't know' than guess."
            HallucinationType.FRONTIER_OOD ->
                "Context is out-of-distribution (low STLE accessibility). Defer to cloud or human."
            else ->
                "Potential hallucination detected. Exercise caution."
        }
    }

    /**
     * Types of hallucinations detected
     */
    enum class HallucinationType {
        NONE,
        INCONSISTENT,          // SelfCheckGPT: responses don't agree
        UNSUPPORTED,           // RAG: no factual support found
        OVERSPECIFIC,          // Too many specific claims
        UNCERTAIN_GENERATION,  // Generating despite uncertainty
        FRONTIER_OOD,          // Frontier Dynamics: context is out-of-distribution (low mu_x)
        UNKNOWN
    }

    /**
     * Hallucination detection result
     */
    class HallucinationResult {
        var response: String = ""
        var isHallucination: Boolean = false
        var hallucinationType: HallucinationType = HallucinationType.NONE
        var confidence: Float = 0.0f // Confidence that it's a hallucination

        // Detection signals
        var isConsistent: Boolean = true
        var consistencyScore: Float = 1.0f
        var factualSupportScore: Float = 0.0f
        var uncertaintyScore: Float = 0.0f
        var emotionalBias: Float = 0.0f
        var factThreadScore: Float = 0.0f
        var opinionBasinScore: Float = 0.0f

        // Claims analysis
        var claims: List<String> = mutableListOf()
        var claimCount: Int = 0

        // Frontier Dynamics STLE accessibility
        var frontierAccessibility: Float = 0.5f
        var frontierConfidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN

        override fun toString(): String {
            return String.format(
                "Hallucination: %s (%.2f confidence)\n" +
                        "Type: %s\n" +
                        "Consistency: %.2f\n" +
                        "Factual Support: %.2f\n" +
                        "Uncertainty: %.2f\n" +
                        "Fact Thread: %.2f\n" +
                        "Opinion Basin: %.2f\n" +
                        "Claims: %d",
                if (isHallucination) "YES" else "NO",
                confidence,
                hallucinationType,
                consistencyScore,
                factualSupportScore,
                uncertaintyScore,
                factThreadScore,
                opinionBasinScore,
                claimCount
            )
        }
    }

    companion object {
        private const val TAG = "HallucinationDetector"
        private const val CONSISTENCY_THRESHOLD = 0.7f
        private const val CONFIDENCE_THRESHOLD = 0.6f

        // Factual support thresholds
        private const val LOW_FACTUAL_SUPPORT_THRESHOLD = 0.3f
        private const val LOW_CONSISTENCY_CLAIMS_THRESHOLD = 0.5f
        private const val HIGH_UNCERTAINTY_THRESHOLD = 0.7f
        private const val MAX_CLAIMS_FOR_OVERSPECIFIC = 5

        // Determination thresholds
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        private const val WEAK_FACTUAL_SUPPORT_THRESHOLD = 0.4f
        private const val WEAK_UNCERTAINTY_THRESHOLD = 0.5f
        private const val WEAK_EMOTIONAL_BIAS_THRESHOLD = -0.1f
        private const val WEAK_FRONTIER_THRESHOLD = 0.3f // STLE mu_x below this = weak OOD signal
        private const val WEAK_FACT_THREAD_THRESHOLD = 0.35f
        private const val WEAK_OPINION_BASIN_THRESHOLD = 0.35f
        private const val MIN_WEAK_SIGNALS_FOR_HALLUCINATION = 3
        private const val FACT_THREAD_CONFIDENCE_BOOST = 0.65f
        private const val LOW_FACT_THREAD_THRESHOLD = 0.25f
        private const val LOW_OPINION_BASIN_THRESHOLD = 0.30f
        private const val MIN_FACTUAL_CLAIMS_FOR_THREAD_CHECK = 3

        // Word overlap
        private const val MIN_WORD_LENGTH_FOR_OVERLAP = 3

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "it", "in", "on", "to", "of",
            "and", "or", "for", "at", "by", "from", "with", "that",
            "this", "but", "not", "are", "was", "were", "been", "has",
            "have", "had", "will", "would", "could", "should", "may",
            "can", "do", "did", "does", "than", "then", "also", "just"
        )
    }
}
