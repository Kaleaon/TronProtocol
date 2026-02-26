package com.tronprotocol.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpisodicReplayBufferTest {

    private lateinit var context: Context
    private lateinit var buffer: EpisodicReplayBuffer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        buffer = EpisodicReplayBuffer(context, "test_ai_${System.nanoTime()}")
    }

    @Test
    fun addEpisode_increasesSize() {
        val statsBefore = buffer.getStats()
        val sizeBefore = statsBefore["total_episodes"] as Int

        buffer.recordEpisode(
            perception = "User asked about weather",
            decision = "Fetch weather data",
            action = "web_search",
            outcome = "Returned sunny forecast",
            reward = 0.8f
        )

        val statsAfter = buffer.getStats()
        val sizeAfter = statsAfter["total_episodes"] as Int
        assertEquals("Size should increase by 1 after adding episode", sizeBefore + 1, sizeAfter)
    }

    @Test
    fun sample_returnsEpisodes() {
        buffer.recordEpisode("perception1", "decision1", "action1", "outcome1", 0.5f)
        buffer.recordEpisode("perception2", "decision2", "action2", "outcome2", 0.7f)
        buffer.recordEpisode("perception3", "decision3", "action3", "outcome3", 0.3f)

        val recent = buffer.getRecent(10)
        assertNotNull("getRecent should not return null", recent)
        assertEquals("Should return 3 episodes", 3, recent.size)
    }

    @Test
    fun sampleCount_isCappedAtBufferSize() {
        buffer.recordEpisode("p1", "d1", "a1", "o1", 0.5f)
        buffer.recordEpisode("p2", "d2", "a2", "o2", 0.6f)

        // Request more than available
        val recent = buffer.getRecent(100)
        assertEquals("Should return at most the number of episodes in buffer", 2, recent.size)
    }

    @Test
    fun emptyBuffer_hasSizeZero() {
        val stats = buffer.getStats()
        assertEquals("Empty buffer should have size 0", 0, stats["total_episodes"])
    }

    @Test
    fun buffer_respectsCapacityLimit() {
        // The buffer has a max capacity of 500
        // Add 510 episodes and verify the buffer does not exceed 500
        for (i in 1..510) {
            buffer.recordEpisode(
                perception = "perception_$i",
                decision = "decision_$i",
                action = "action_$i",
                outcome = "outcome_$i",
                reward = (i % 10) / 10.0f
            )
        }

        val stats = buffer.getStats()
        val totalEpisodes = stats["total_episodes"] as Int
        assertTrue(
            "Buffer should not exceed capacity of 500, got $totalEpisodes",
            totalEpisodes <= 500
        )
    }
}
