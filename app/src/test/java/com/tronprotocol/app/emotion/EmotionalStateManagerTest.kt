package com.tronprotocol.app.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EmotionalStateManagerTest {

    private lateinit var manager: EmotionalStateManager

    @Before
    fun setUp() {
        manager = EmotionalStateManager(RuntimeEnvironment.getApplication())
    }

    @Test
    fun initialEmotion_isNeutral() {
        val state = manager.getEmotionalState()
        assertEquals(
            "Initial emotion should be NEUTRAL",
            EmotionalStateManager.Emotion.NEUTRAL.name,
            state["current_emotion"]
        )
    }

    @Test
    fun applyConfidence_changesCurrentEmotion() {
        manager.applyConfidence("test context")

        val state = manager.getEmotionalState()
        assertEquals(
            "After applyConfidence, current emotion should be CONFIDENT",
            EmotionalStateManager.Emotion.CONFIDENT.name,
            state["current_emotion"]
        )
    }

    @Test
    fun applyEmbarrassment_changesCurrentEmotion() {
        manager.applyEmbarrassment("hallucination detected", 0.8f)

        val state = manager.getEmotionalState()
        assertEquals(
            "After applyEmbarrassment, current emotion should be EMBARRASSED",
            EmotionalStateManager.Emotion.EMBARRASSED.name,
            state["current_emotion"]
        )
    }

    @Test
    fun expressUncertainty_changesCurrentEmotion() {
        manager.expressUncertainty("unsure about this")

        val state = manager.getEmotionalState()
        assertEquals(
            "After expressUncertainty, current emotion should be UNCERTAIN",
            EmotionalStateManager.Emotion.UNCERTAIN.name,
            state["current_emotion"]
        )
    }

    @Test
    fun applyPride_changesCurrentEmotion() {
        manager.applyPride("learned from mistake")

        val state = manager.getEmotionalState()
        assertEquals(
            "After applyPride, current emotion should be PROUD",
            EmotionalStateManager.Emotion.PROUD.name,
            state["current_emotion"]
        )
    }

    @Test
    fun applyCuriosity_changesCurrentEmotion() {
        manager.applyCuriosity("new topic discovered")

        val state = manager.getEmotionalState()
        assertEquals(
            "After applyCuriosity, current emotion should be CURIOUS",
            EmotionalStateManager.Emotion.CURIOUS.name,
            state["current_emotion"]
        )
    }

    @Test
    fun getEmotionalState_returnsEmotionHistory() {
        manager.applyConfidence("first event")
        manager.applyEmbarrassment("second event", 0.5f)
        manager.applyCuriosity("third event")

        val state = manager.getEmotionalState()
        val historySize = state["history_size"] as Int
        assertTrue(
            "History should contain recorded emotions, got size $historySize",
            historySize >= 3
        )

        // Check emotion distribution
        @Suppress("UNCHECKED_CAST")
        val distribution = state["emotion_distribution"] as Map<String, Int>
        assertTrue(
            "Distribution should contain CONFIDENT",
            distribution.containsKey(EmotionalStateManager.Emotion.CONFIDENT.name)
        )
        assertTrue(
            "Distribution should contain EMBARRASSED",
            distribution.containsKey(EmotionalStateManager.Emotion.EMBARRASSED.name)
        )
        assertTrue(
            "Distribution should contain CURIOUS",
            distribution.containsKey(EmotionalStateManager.Emotion.CURIOUS.name)
        )
    }

    @Test
    fun getPersonalityTraits_returnsMapOfTraits() {
        val traits = manager.getPersonalityTraits()
        assertNotNull("Personality traits should not be null", traits)
        // Initially might be empty since no traits have been reinforced
        // After applying curiosity, the curiosity trait should appear
        manager.applyCuriosity("learning something new")
        val traitsAfter = manager.getPersonalityTraits()
        assertTrue(
            "After curiosity, personality traits should contain 'curiosity'",
            traitsAfter.containsKey("curiosity")
        )
    }

    @Test
    fun shouldDefer_returnsTrueForVeryLowConfidence() {
        // With no prior embarrassment, emotional bias is 0
        // shouldDefer returns true when adjustedConfidence < 0.5
        assertTrue(
            "Should defer when confidence is very low (0.1)",
            manager.shouldDefer(0.1f)
        )
        assertTrue(
            "Should defer when confidence is low (0.3)",
            manager.shouldDefer(0.3f)
        )
        assertTrue(
            "Should defer when confidence is just below threshold (0.49)",
            manager.shouldDefer(0.49f)
        )
    }

    @Test
    fun shouldDefer_returnsFalseForHighConfidence() {
        assertFalse(
            "Should not defer when confidence is high (0.8)",
            manager.shouldDefer(0.8f)
        )
        assertFalse(
            "Should not defer when confidence is at threshold (0.5)",
            manager.shouldDefer(0.5f)
        )
        assertFalse(
            "Should not defer when confidence is very high (1.0)",
            manager.shouldDefer(1.0f)
        )
    }

    @Test
    fun getCuriosityStreak_tracksCuriousEpisodes() {
        // Initially, the curiosity streak should not be active
        assertFalse(
            "Should not be in curiosity streak initially",
            manager.isInCuriosityStreak()
        )

        // Apply curiosity once -- streak of 1, not >= 2
        manager.applyCuriosity("first curiosity")
        assertFalse(
            "Single curiosity should not activate streak (need >= 2)",
            manager.isInCuriosityStreak()
        )

        // Apply curiosity again -- streak of 2
        manager.applyCuriosity("second curiosity")
        assertTrue(
            "Two consecutive curiosity events should activate streak",
            manager.isInCuriosityStreak()
        )

        // Apply a third time
        manager.applyCuriosity("third curiosity")
        assertTrue(
            "Three consecutive curiosity events should maintain streak",
            manager.isInCuriosityStreak()
        )
    }

    @Test
    fun applyEmbarrassment_affectsCautionBias() {
        // Before embarrassment, bias should be 0
        assertEquals(
            "Emotional bias should be 0 before any embarrassment",
            0.0f, manager.getEmotionalBias(), 0.0001f
        )

        // Apply embarrassment
        manager.applyEmbarrassment("hallucination in response", 0.8f)

        val bias = manager.getEmotionalBias()
        assertTrue(
            "After embarrassment, emotional bias should be negative, got $bias",
            bias < 0.0f
        )

        // The bias should make shouldDefer more likely
        // With embarrassment bias, even moderate confidence may trigger deference
        val moderateConfidence = 0.55f
        val adjustedConfidence = moderateConfidence + bias
        if (adjustedConfidence < 0.5f) {
            assertTrue(
                "Embarrassment bias should make moderate confidence defer",
                manager.shouldDefer(moderateConfidence)
            )
        }
    }

    @Test
    fun applyEmbarrassment_returnsNegativePenalty() {
        val penalty = manager.applyEmbarrassment("test hallucination", 0.5f)
        assertTrue(
            "Embarrassment penalty should be negative, got $penalty",
            penalty < 0.0f
        )
    }

    @Test
    fun applyConfidence_returnsPositiveBoost() {
        val boost = manager.applyConfidence("verified correct response")
        assertTrue(
            "Confidence boost should be positive, got $boost",
            boost > 0.0f
        )
    }

    @Test
    fun applyPride_returnsPositiveBoost() {
        val boost = manager.applyPride("learned from mistake")
        assertTrue(
            "Pride boost should be positive, got $boost",
            boost > 0.0f
        )
    }

    @Test
    fun applyCuriosity_returnsPositiveBoost() {
        val boost = manager.applyCuriosity("exploring new topic")
        assertTrue(
            "Curiosity boost should be positive, got $boost",
            boost > 0.0f
        )
    }

    @Test
    fun checkConsistency_detectsHighSimilarity() {
        val responses = listOf(
            "The Eiffel Tower is located in Paris, France.",
            "The Eiffel Tower can be found in Paris, France.",
            "Located in Paris France, the Eiffel Tower stands tall."
        )

        val result = manager.checkConsistency(responses)
        assertNotNull(result)
        assertTrue(
            "Similar responses should show moderate to high consistency",
            result.similarityScore > 0.3f
        )
    }

    @Test
    fun checkConsistency_handlesNullInput() {
        val result = manager.checkConsistency(null)
        assertFalse("Null input should return not consistent", result.isConsistent)
        assertEquals(0.0f, result.similarityScore, 0.0001f)
    }

    @Test
    fun checkConsistency_handlesSingleResponse() {
        val result = manager.checkConsistency(listOf("single response"))
        assertFalse("Single response should return not consistent", result.isConsistent)
    }

    @Test
    fun reinforceTrait_affectsTraitValue() {
        val traitName = "thoroughness"
        val initialValue = manager.getTraitValue(traitName)
        assertEquals("Initial trait value should be 0.5 (neutral)", 0.5f, initialValue, 0.0001f)

        manager.reinforceTrait(traitName, 1.0f)
        val afterReinforcement = manager.getTraitValue(traitName)
        assertTrue(
            "Trait value should increase after positive reinforcement, got $afterReinforcement",
            afterReinforcement > initialValue
        )
    }

    @Test
    fun getPersonalityProfile_returnsExpectedKeys() {
        manager.applyCuriosity("test")
        val profile = manager.getPersonalityProfile()

        assertNotNull(profile)
        assertTrue(profile.containsKey("dominant_traits"))
        assertTrue(profile.containsKey("balanced_traits"))
        assertTrue(profile.containsKey("weak_traits"))
        assertTrue(profile.containsKey("trait_count"))
        assertTrue(profile.containsKey("curiosity_streak"))
    }

    @Test
    fun emotionEnum_hasAllExpectedValues() {
        val emotions = EmotionalStateManager.Emotion.entries
        assertEquals("Should have 6 emotions", 6, emotions.size)

        assertNotNull(EmotionalStateManager.Emotion.CONFIDENT)
        assertNotNull(EmotionalStateManager.Emotion.UNCERTAIN)
        assertNotNull(EmotionalStateManager.Emotion.EMBARRASSED)
        assertNotNull(EmotionalStateManager.Emotion.PROUD)
        assertNotNull(EmotionalStateManager.Emotion.CURIOUS)
        assertNotNull(EmotionalStateManager.Emotion.NEUTRAL)
    }
}
