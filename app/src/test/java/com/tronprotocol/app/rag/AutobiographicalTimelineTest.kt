package com.tronprotocol.app.rag

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class AutobiographicalTimelineTest {

    private lateinit var timeline: AutobiographicalTimeline
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        timeline = AutobiographicalTimeline(context, "test_autobio_ai")
    }

    @Test
    fun `empty timeline has size zero`() {
        val stats = timeline.getStats()
        assertEquals(0, stats["total_milestones"])
    }

    @Test
    fun `recordMilestone increases size`() {
        timeline.recordMilestone("learning", "First learning event", 0.7f)

        val stats = timeline.getStats()
        assertEquals(1, stats["total_milestones"])
    }

    @Test
    fun `recordMilestone returns milestone object`() {
        val milestone = timeline.recordMilestone("discovery", "Found a new concept", 0.8f)

        assertNotNull(milestone)
        assertEquals("discovery", milestone.category)
        assertEquals("Found a new concept", milestone.description)
        assertEquals(0.8f, milestone.significance, 0.001f)
        assertTrue(milestone.timestamp > 0)
        assertTrue(milestone.id.startsWith("ms_"))
    }

    @Test
    fun `getTimeline returns added milestones`() {
        timeline.recordMilestone("learning", "Learned about AI", 0.6f)
        timeline.recordMilestone("interaction", "First user interaction", 0.9f)

        val milestones = timeline.getTimeline()
        assertEquals(2, milestones.size)
        // Timeline is sorted by timestamp descending, so most recent first
        assertEquals("interaction", milestones[0].category)
        assertEquals("learning", milestones[1].category)
    }

    @Test
    fun `getTimeline respects limit`() {
        timeline.recordMilestone("cat1", "Event 1", 0.5f)
        timeline.recordMilestone("cat2", "Event 2", 0.5f)
        timeline.recordMilestone("cat3", "Event 3", 0.5f)

        val milestones = timeline.getTimeline(limit = 2)
        assertEquals(2, milestones.size)
    }

    @Test
    fun `getByCategory filters milestones correctly`() {
        timeline.recordMilestone("learning", "Learn event 1", 0.5f)
        timeline.recordMilestone("interaction", "Interact event", 0.5f)
        timeline.recordMilestone("learning", "Learn event 2", 0.7f)

        val learningMilestones = timeline.getByCategory("learning")
        assertEquals(2, learningMilestones.size)
        assertTrue(learningMilestones.all { it.category == "learning" })
    }

    @Test
    fun `getByCategory returns empty for nonexistent category`() {
        timeline.recordMilestone("learning", "Some event", 0.5f)

        val results = timeline.getByCategory("nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `first milestone in a category is marked as isFirst`() {
        val first = timeline.recordMilestone("new_category", "First in category", 0.5f)
        assertTrue(first.isFirst)

        val second = timeline.recordMilestone("new_category", "Second in category", 0.5f)
        assertFalse(second.isFirst)
    }

    @Test
    fun `getFirsts returns first occurrence timestamps`() {
        timeline.recordMilestone("learning", "First learning", 0.5f)
        timeline.recordMilestone("interaction", "First interaction", 0.5f)

        val firsts = timeline.getFirsts()
        assertEquals(2, firsts.size)
        assertTrue(firsts.containsKey("learning"))
        assertTrue(firsts.containsKey("interaction"))
    }

    @Test
    fun `getMostSignificant returns milestones sorted by significance`() {
        timeline.recordMilestone("low", "Low significance", 0.2f)
        timeline.recordMilestone("high", "High significance", 0.9f)
        timeline.recordMilestone("medium", "Medium significance", 0.5f)

        val mostSignificant = timeline.getMostSignificant(2)
        assertEquals(2, mostSignificant.size)
        assertEquals("high", mostSignificant[0].category)
        assertEquals("medium", mostSignificant[1].category)
    }

    @Test
    fun `generateNarrative returns non-empty string`() {
        timeline.recordMilestone("learning", "Learned something", 0.5f)
        timeline.recordMilestone("interaction", "User talked", 0.7f)

        val narrative = timeline.generateNarrative()
        assertNotNull(narrative)
        assertTrue(narrative.isNotEmpty())
        assertTrue(narrative.contains("milestones"))
    }

    @Test
    fun `generateNarrative works for empty timeline`() {
        val narrative = timeline.generateNarrative()
        assertNotNull(narrative)
        assertTrue(narrative.contains("0 milestones"))
    }

    @Test
    fun `getStats returns all expected keys`() {
        val stats = timeline.getStats()
        assertTrue(stats.containsKey("total_milestones"))
        assertTrue(stats.containsKey("total_categories"))
        assertTrue(stats.containsKey("total_firsts"))
        assertTrue(stats.containsKey("avg_significance"))
    }

    @Test
    fun `milestone metadata can be provided`() {
        val milestone = timeline.recordMilestone(
            "test",
            "With metadata",
            0.5f,
            metadata = mapOf("key1" to "value1", "key2" to 42)
        )
        assertEquals("value1", milestone.metadata["key1"])
        assertEquals(42, milestone.metadata["key2"])
    }
}
