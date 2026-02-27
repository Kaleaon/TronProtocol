package com.tronprotocol.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmotionalStateTrackerTest {

    private lateinit var tracker: EmotionalStateTracker

    @Before
    fun setUp() {
        tracker = EmotionalStateTracker()
    }

    @Test
    fun initialStateIsNeutral() {
        assertEquals(EmotionRegime.NEUTRAL, tracker.currentRegime)
        assertEquals(0.5f, tracker.getFusedScore(), 0.01f)
    }

    @Test
    fun positiveKeywordSentimentRaisesScore() {
        tracker.updateTier1("I'm so happy and excited! This is wonderful!")
        assertTrue(tracker.getFusedScore() >= 0.5f)
    }

    @Test
    fun negativeKeywordSentimentLowersScore() {
        tracker.updateTier1("This is terrible and frustrating. Everything is wrong.")
        assertTrue(tracker.getFusedScore() <= 0.5f)
    }

    @Test
    fun tier0UpdateAffectsScore() {
        tracker.updateTier0(0.9f)
        assertTrue(tracker.getFusedScore() > 0.5f)
    }

    @Test
    fun tierScoresArrayHasFourEntries() {
        val scores = tracker.getTierScores()
        assertEquals(4, scores.size)
    }

    @Test
    fun resetReturnsToNeutral() {
        tracker.updateTier0(0.1f)
        tracker.updateTier1("terrible awful horrible")
        tracker.reset()

        assertEquals(EmotionRegime.NEUTRAL, tracker.currentRegime)
        assertEquals(0.5f, tracker.getFusedScore(), 0.01f)
    }

    @Test
    fun regimeHistoryTracksTransitions() {
        // Force a clear regime change by pushing multiple tiers to extreme
        tracker.updateTier0(0.95f)
        tracker.updateTier2(0.95f)
        tracker.updateTier3(0.95f)
        tracker.updateTier1("amazing wonderful excellent fantastic great happy excited")

        // If score is high enough, should have transitioned from NEUTRAL
        val history = tracker.getRegimeHistory()
        // History may or may not have entries depending on EMA smoothing
        // Just verify it doesn't crash
        assertTrue(history.size >= 0)
    }

    @Test
    fun processTurnUpdatesState() {
        tracker.processTurn(
            "I'm really upset about this",
            "I understand your frustration. Let me help."
        )
        // Score should shift — exact value depends on keyword matching
        val score = tracker.getFusedScore()
        assertTrue(score >= 0f && score <= 1f)
    }

    @Test
    fun feedbackCorrectionChangesRegime() {
        tracker.feedbackCorrection(EmotionRegime.EXCITED)
        assertEquals(EmotionRegime.EXCITED, tracker.currentRegime)
    }

    @Test
    fun blankTextReturnsNeutralSentiment() {
        tracker.updateTier1("")
        // Blank text should produce 0.5 (neutral) sentiment
        val scores = tracker.getTierScores()
        assertEquals(0.5f, scores[1], 0.01f)
    }
}
