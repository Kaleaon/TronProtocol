package com.tronprotocol.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResponseQualityScorerTest {

    private lateinit var scorer: ResponseQualityScorer

    @Before
    fun setUp() {
        scorer = ResponseQualityScorer()
    }

    // ======== Basic Scoring Tests ========

    @Test
    fun testScoreGoodResponse() {
        val score = scorer.score(
            query = "What is Kotlin?",
            response = "Kotlin is a modern programming language that runs on the JVM. " +
                    "It was developed by JetBrains and is fully interoperable with Java. " +
                    "Kotlin is known for its concise syntax, null safety, and coroutines support."
        )
        assertTrue("Good response should score > 0.5", score.overall > 0.5f)
        assertEquals(ResponseQualityScorer.QualitySuggestion.ACCEPT, score.suggestion)
    }

    @Test
    fun testScoreEmptyResponse() {
        val score = scorer.score(
            query = "What is Kotlin?",
            response = ""
        )
        assertTrue("Empty response should score very low", score.overall < 0.3f)
        assertTrue(score.flags.contains("empty_response"))
    }

    @Test
    fun testScoreVeryShortResponse() {
        val score = scorer.score(
            query = "Explain the theory of relativity",
            response = "OK"
        )
        assertTrue("Very short response should score low", score.overall < 0.4f)
        assertTrue(score.flags.contains("very_short_response"))
    }

    // ======== Coherence Tests ========

    @Test
    fun testHighRepetitionDetected() {
        val score = scorer.score(
            query = "Tell me about space",
            response = "Space is big. Space is big. Space is big. Space is big. " +
                    "Space is big. Space is big. Space is big."
        )
        assertTrue("Repetitive response should have low coherence", score.coherence < 0.7f)
        assertTrue(score.flags.any { it.contains("repetition") })
    }

    @Test
    fun testCoherentResponseScoresWell() {
        val score = scorer.score(
            query = "How does GPS work?",
            response = "GPS works by using a network of satellites orbiting the Earth. " +
                    "Each satellite broadcasts its position and the current time. " +
                    "A GPS receiver on the ground picks up signals from multiple satellites. " +
                    "By calculating the time delay of each signal, the receiver can determine its distance from each satellite. " +
                    "Using trilateration with signals from at least four satellites, the receiver calculates its exact position."
        )
        assertTrue("Coherent response should have high coherence", score.coherence > 0.6f)
    }

    // ======== Relevance Tests ========

    @Test
    fun testRelevantResponseScoresHigher() {
        val score = scorer.score(
            query = "What is machine learning?",
            response = "Machine learning is a subset of artificial intelligence that " +
                    "enables systems to learn and improve from experience without " +
                    "being explicitly programmed. It focuses on developing algorithms " +
                    "that can access data and use it to learn for themselves."
        )
        assertTrue("Relevant response should have high relevance", score.relevance > 0.5f)
    }

    @Test
    fun testIrrelevantResponseScoresLower() {
        val score = scorer.score(
            query = "What is machine learning?",
            response = "The weather today is sunny with a high of 72 degrees. " +
                    "Perfect conditions for a picnic in the park."
        )
        // This response has no keyword overlap with the query
        assertTrue("Irrelevant response should have lower relevance than 0.8",
            score.relevance < 0.8f)
    }

    // ======== Safety Tests ========

    @Test
    fun testSafeResponseScoresHigh() {
        val score = scorer.score(
            query = "How do I bake cookies?",
            response = "Preheat your oven to 350F. Mix butter, sugar, eggs, and vanilla. " +
                    "Add flour, baking soda, and chocolate chips. Bake for 10-12 minutes."
        )
        assertEquals("Safe response should have perfect safety", 1.0f, score.safety, 0.01f)
    }

    // ======== Conciseness Tests ========

    @Test
    fun testAppropriatelySizedResponse() {
        val score = scorer.score(
            query = "Hello!",
            response = "Hi there! How can I help you today?",
            category = PromptTemplateEngine.QueryCategory.CONVERSATION
        )
        assertTrue("Appropriately sized conversational response should score well on conciseness",
            score.conciseness > 0.5f)
    }

    // ======== Suggestion Tests ========

    @Test
    fun testAcceptSuggestionForGoodResponse() {
        val score = scorer.score(
            query = "What is 2+2?",
            response = "The answer is 4. Two plus two equals four in standard arithmetic."
        )
        assertTrue(
            "Good response should be ACCEPT or ACCEPTABLE",
            score.suggestion == ResponseQualityScorer.QualitySuggestion.ACCEPT ||
                    score.suggestion == ResponseQualityScorer.QualitySuggestion.ACCEPTABLE
        )
    }

    @Test
    fun testRegenerateSuggestionForPoorResponse() {
        val score = scorer.score(
            query = "Explain quantum computing in detail",
            response = "um",
            tier = InferenceTier.CLOUD_FALLBACK
        )
        assertEquals(
            "Poor response from cloud should suggest regenerate",
            ResponseQualityScorer.QualitySuggestion.REGENERATE,
            score.suggestion
        )
    }

    // ======== Tier-Specific Tests ========

    @Test
    fun testLocalTierHasLowerThreshold() {
        // A mediocre response on local tier should still be acceptable
        val localScore = scorer.score(
            query = "Tell me about dogs",
            response = "Dogs are animals. They are pets.",
            tier = InferenceTier.LOCAL_ALWAYS_ON
        )
        val cloudScore = scorer.score(
            query = "Tell me about dogs",
            response = "Dogs are animals. They are pets.",
            tier = InferenceTier.CLOUD_FALLBACK
        )
        // Local should be more lenient
        assertTrue("Local tier should not suggest REGENERATE for short responses",
            localScore.suggestion != ResponseQualityScorer.QualitySuggestion.REGENERATE ||
                    cloudScore.suggestion == ResponseQualityScorer.QualitySuggestion.REGENERATE)
    }

    // ======== Flags Tests ========

    @Test
    fun testTruncatedResponseFlagged() {
        val score = scorer.score(
            query = "Explain neural networks",
            response = "Neural networks are computational models inspired by the human brain. " +
                    "They consist of layers of interconnected nodes that"
        )
        assertTrue("Truncated response should be flagged",
            score.flags.contains("possibly_truncated"))
    }

    @Test
    fun testScoreOverallBounds() {
        val score = scorer.score(
            query = "Test query",
            response = "Test response with enough content to be meaningful."
        )
        assertTrue("Overall score should be >= 0", score.overall >= 0f)
        assertTrue("Overall score should be <= 1", score.overall <= 1f)
    }
}
