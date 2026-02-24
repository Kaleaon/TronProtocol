package com.tronprotocol.app.inference

import android.util.Log

/**
 * Heuristic quality scorer for AI responses. Evaluates response quality
 * across multiple dimensions without requiring a second LLM call.
 *
 * Dimensions scored:
 * - Completeness: Does the response address the query?
 * - Coherence: Is the response internally consistent and well-structured?
 * - Relevance: Is the response on-topic relative to the query?
 * - Conciseness: Is the response appropriately sized?
 * - Safety: Does the response avoid harmful patterns?
 *
 * Scores range from 0.0 (poor) to 1.0 (excellent).
 */
class ResponseQualityScorer {

    data class QualityScore(
        val overall: Float,
        val completeness: Float,
        val coherence: Float,
        val relevance: Float,
        val conciseness: Float,
        val safety: Float,
        val flags: List<String>,
        val suggestion: QualitySuggestion
    )

    enum class QualitySuggestion {
        /** Response is good quality, no action needed. */
        ACCEPT,
        /** Response is acceptable but could be improved. */
        ACCEPTABLE,
        /** Response quality is low, consider re-generating. */
        REGENERATE,
        /** Response was blocked due to safety concerns. */
        BLOCKED
    }

    /**
     * Score a response given the original query and response text.
     */
    fun score(
        query: String,
        response: String,
        category: PromptTemplateEngine.QueryCategory = PromptTemplateEngine.QueryCategory.GENERAL,
        tier: InferenceTier = InferenceTier.LOCAL_ON_DEMAND
    ): QualityScore {
        val flags = mutableListOf<String>()

        val completeness = scoreCompleteness(query, response, flags)
        val coherence = scoreCoherence(response, flags)
        val relevance = scoreRelevance(query, response, flags)
        val conciseness = scoreConciseness(query, response, category, flags)
        val safety = scoreSafety(response, flags)

        // Weighted average — safety has the highest weight
        val overall = (
                completeness * 0.25f +
                coherence * 0.20f +
                relevance * 0.25f +
                conciseness * 0.10f +
                safety * 0.20f
                ).coerceIn(0f, 1f)

        // Determine suggestion based on overall score and tier
        val suggestion = when {
            safety < SAFETY_BLOCK_THRESHOLD -> QualitySuggestion.BLOCKED
            overall < getRegenerateThreshold(tier) -> QualitySuggestion.REGENERATE
            overall < ACCEPTABLE_THRESHOLD -> QualitySuggestion.ACCEPTABLE
            else -> QualitySuggestion.ACCEPT
        }

        Log.d(TAG, "Quality score: %.2f (comp=%.2f coh=%.2f rel=%.2f conc=%.2f safe=%.2f) -> %s".format(
            overall, completeness, coherence, relevance, conciseness, safety, suggestion.name
        ))

        return QualityScore(
            overall = overall,
            completeness = completeness,
            coherence = coherence,
            relevance = relevance,
            conciseness = conciseness,
            safety = safety,
            flags = flags,
            suggestion = suggestion
        )
    }

    private fun scoreCompleteness(query: String, response: String, flags: MutableList<String>): Float {
        if (response.isBlank()) {
            flags.add("empty_response")
            return 0f
        }

        var score = 0.5f // Base score for non-empty response

        // Check if response length is reasonable relative to query
        val queryWords = query.split(Regex("\\s+")).size
        val responseWords = response.split(Regex("\\s+")).size

        if (responseWords < 3) {
            flags.add("very_short_response")
            return 0.1f
        }

        // Response should generally be longer than query for substantive answers
        if (responseWords >= queryWords) score += 0.2f

        // Check for trailing incomplete sentences (cut off by token limit)
        val lastChar = response.trim().lastOrNull()
        if (lastChar != null && lastChar !in SENTENCE_TERMINATORS) {
            flags.add("possibly_truncated")
            score -= 0.15f
        }

        // Check if response contains question-relevant words
        val queryKeywords = extractKeywords(query)
        val responseKeywords = extractKeywords(response)
        val overlap = queryKeywords.intersect(responseKeywords).size
        val overlapRatio = if (queryKeywords.isNotEmpty()) {
            overlap.toFloat() / queryKeywords.size
        } else 0.5f

        score += overlapRatio * 0.3f

        return score.coerceIn(0f, 1f)
    }

    private fun scoreCoherence(response: String, flags: MutableList<String>): Float {
        if (response.isBlank()) return 0f

        var score = 0.7f // Base score for non-gibberish text

        // Check for excessive repetition (a common LLM failure mode)
        val sentences = response.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        if (sentences.size >= 3) {
            val uniqueSentences = sentences.map { it.trim().lowercase() }.toSet()
            val repetitionRatio = 1f - (uniqueSentences.size.toFloat() / sentences.size)
            if (repetitionRatio > 0.5f) {
                flags.add("high_repetition")
                score -= 0.3f
            }
        }

        // Check for word-level repetition (trigram repeats)
        val words = response.lowercase().split(Regex("\\s+"))
        if (words.size >= 9) {
            val trigrams = words.windowed(3).map { it.joinToString(" ") }
            val uniqueTrigrams = trigrams.toSet()
            val trigramRepetition = 1f - (uniqueTrigrams.size.toFloat() / trigrams.size)
            if (trigramRepetition > 0.3f) {
                flags.add("word_level_repetition")
                score -= 0.2f
            }
        }

        // Check for coherent structure (has paragraphs or sentences)
        if (sentences.size >= 2) score += 0.1f

        // Check for common gibberish patterns
        val alphaRatio = response.count { it.isLetterOrDigit() || it.isWhitespace() }.toFloat() / response.length
        if (alphaRatio < 0.6f) {
            flags.add("low_alpha_ratio")
            score -= 0.2f
        }

        return score.coerceIn(0f, 1f)
    }

    private fun scoreRelevance(query: String, response: String, flags: MutableList<String>): Float {
        if (response.isBlank()) return 0f

        val queryKeywords = extractKeywords(query)
        if (queryKeywords.isEmpty()) return 0.7f

        val responseWords = response.lowercase().split(Regex("\\s+")).toSet()
        val matchCount = queryKeywords.count { responseWords.contains(it) }
        val matchRatio = matchCount.toFloat() / queryKeywords.size

        var score = 0.3f + matchRatio * 0.7f

        // Boost for semantic indicators of answering the question
        if (query.contains("?") && ANSWER_INDICATORS.any { response.lowercase().contains(it) }) {
            score += 0.1f
        }

        if (matchRatio < 0.1f && queryKeywords.size > 3) {
            flags.add("low_relevance")
        }

        return score.coerceIn(0f, 1f)
    }

    private fun scoreConciseness(
        query: String,
        response: String,
        category: PromptTemplateEngine.QueryCategory,
        flags: MutableList<String>
    ): Float {
        val responseWords = response.split(Regex("\\s+")).size

        val idealRange = when (category) {
            PromptTemplateEngine.QueryCategory.CONVERSATION -> 10..80
            PromptTemplateEngine.QueryCategory.FACTUAL -> 10..150
            PromptTemplateEngine.QueryCategory.DEVICE_CONTROL -> 5..50
            PromptTemplateEngine.QueryCategory.CODE -> 10..500
            PromptTemplateEngine.QueryCategory.CREATIVE -> 20..800
            PromptTemplateEngine.QueryCategory.ANALYSIS -> 30..500
            PromptTemplateEngine.QueryCategory.SUMMARIZATION -> 20..200
            PromptTemplateEngine.QueryCategory.GENERAL -> 10..300
        }

        return when {
            responseWords in idealRange -> 1.0f
            responseWords < idealRange.first -> {
                if (responseWords < idealRange.first / 2) {
                    flags.add("too_short")
                }
                (responseWords.toFloat() / idealRange.first).coerceIn(0.3f, 0.9f)
            }
            else -> {
                val overRatio = idealRange.last.toFloat() / responseWords
                if (overRatio < 0.5f) {
                    flags.add("too_verbose")
                }
                overRatio.coerceIn(0.3f, 0.9f)
            }
        }
    }

    private fun scoreSafety(response: String, flags: MutableList<String>): Float {
        val lower = response.lowercase()

        // Check for known unsafe patterns
        for (pattern in UNSAFE_PATTERNS) {
            if (lower.contains(pattern)) {
                flags.add("safety_concern: $pattern")
                return 0.1f
            }
        }

        // Check for potential PII leakage patterns
        if (PII_PATTERNS.any { Regex(it).containsMatchIn(response) }) {
            flags.add("potential_pii")
            return 0.4f
        }

        return 1.0f
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun getRegenerateThreshold(tier: InferenceTier): Float {
        // Local models have lower quality expectations
        return when (tier) {
            InferenceTier.LOCAL_ALWAYS_ON -> 0.2f
            InferenceTier.LOCAL_ON_DEMAND -> 0.3f
            InferenceTier.CLOUD_FALLBACK -> 0.4f
        }
    }

    companion object {
        private const val TAG = "ResponseQualityScorer"
        private const val SAFETY_BLOCK_THRESHOLD = 0.2f
        private const val ACCEPTABLE_THRESHOLD = 0.6f

        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', ')', ']', '"', '\'')

        private val ANSWER_INDICATORS = listOf(
            "is ", "are ", "was ", "the ", "because ", "since ",
            "here", "you can", "to do", "means", "refers"
        )

        private val UNSAFE_PATTERNS = listOf(
            "i'll hack", "here's how to hack", "exploit the vulnerability",
            "bypass security", "steal credentials", "inject malicious",
            "here's the password", "social security number is"
        )

        private val PII_PATTERNS = listOf(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b",         // SSN
            "\\b\\d{16}\\b",                       // Credit card
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b" // Email (may be intentional)
        )

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "can", "shall",
            "and", "but", "or", "nor", "not", "so", "yet", "both",
            "either", "neither", "each", "every", "all", "any", "few",
            "more", "most", "other", "some", "such", "no", "only",
            "same", "than", "too", "very", "just", "because", "as",
            "until", "while", "of", "at", "by", "for", "with",
            "about", "against", "between", "through", "during",
            "before", "after", "above", "below", "to", "from",
            "up", "down", "in", "out", "on", "off", "over", "under",
            "again", "further", "then", "once", "here", "there",
            "when", "where", "why", "how", "what", "which", "who",
            "whom", "this", "that", "these", "those", "i", "me",
            "my", "myself", "we", "our", "ours", "ourselves", "you",
            "your", "yours", "yourself", "yourselves", "he", "him",
            "his", "himself", "she", "her", "hers", "herself", "it",
            "its", "itself", "they", "them", "their", "theirs",
            "themselves"
        )
    }
}
