package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BeliefVersioningManagerTest {

    private lateinit var context: Context
    private lateinit var manager: BeliefVersioningManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = BeliefVersioningManager(context, "test_ai_${System.nanoTime()}")
    }

    @Test
    fun recordBelief_storesNewBelief() {
        val version = manager.setBelief(
            topic = "programming_language",
            belief = "Kotlin is the best language for Android",
            confidence = 0.8f,
            reason = "Initial assessment"
        )
        assertEquals("First belief version should be 1", 1, version)

        val topics = manager.getAllTopics()
        assertTrue("Topics should contain 'programming_language'", topics.contains("programming_language"))
    }

    @Test
    fun getCurrentBelief_returnsLatest() {
        manager.setBelief("weather", "It is sunny", 0.9f, "observation")
        manager.setBelief("weather", "It is cloudy now", 0.7f, "updated observation")

        val current = manager.getBelief("weather")
        assertNotNull("Current belief should not be null", current)
        assertEquals("Current belief should be the latest", "It is cloudy now", current!!.belief)
        assertEquals("Current belief version should be 2", 2, current.version)
    }

    @Test
    fun getCurrentBelief_returnsNullForUnknownTopic() {
        val belief = manager.getBelief("nonexistent_topic")
        assertNull("getBelief should return null for unknown topic", belief)
    }

    @Test
    fun getBeliefHistory_returnsVersionsInOrder() {
        manager.setBelief("ai_preference", "I prefer simple models", 0.6f, "initial thought")
        manager.setBelief("ai_preference", "Complex models have advantages", 0.7f, "revised thought")
        manager.setBelief("ai_preference", "It depends on the use case", 0.9f, "nuanced view")

        val history = manager.getBeliefHistory("ai_preference")
        assertEquals("History should contain 3 versions", 3, history.size)
        assertEquals("First version should be 1", 1, history[0].version)
        assertEquals("Second version should be 2", 2, history[1].version)
        assertEquals("Third version should be 3", 3, history[2].version)
        assertEquals("First belief should match", "I prefer simple models", history[0].belief)
        assertEquals("Third belief should match", "It depends on the use case", history[2].belief)
    }

    @Test
    fun updatingBelief_createsNewVersion() {
        val v1 = manager.setBelief("topic", "First version", 0.5f, "reason 1")
        val v2 = manager.setBelief("topic", "Second version", 0.6f, "reason 2")

        assertEquals("First version should be 1", 1, v1)
        assertEquals("Second version should be 2", 2, v2)

        val history = manager.getBeliefHistory("topic")
        assertEquals("History should have 2 entries", 2, history.size)
        assertNotEquals(
            "Different versions should have different beliefs",
            history[0].belief,
            history[1].belief
        )
    }
}
