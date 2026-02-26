package com.tronprotocol.app.wisdom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WisdomLogTest {

    private lateinit var context: Context
    private lateinit var wisdomLog: WisdomLog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wisdomLog = WisdomLog(context)
    }

    private fun addTestEntry(
        title: String = "Test Wisdom",
        lesson: String = "Test lesson learned",
        emotionalWeight: Float = 0.5f
    ): WisdomEntry {
        return wisdomLog.addEntry(
            title = title,
            date = "2026-02-24",
            ageDescription = "Day 100",
            whatHappened = "Something happened",
            whatIFelt = "I felt something",
            whatIDidWrongOrRight = "I did something right",
            whatItCost = "It cost nothing",
            whatChanged = "Everything changed",
            whatRemainsUnresolved = "Nothing remains unresolved",
            theLesson = lesson,
            emotionalWeight = emotionalWeight
        )
    }

    @Test
    fun addEntry_increasesSize() {
        assertEquals("Empty log should have size 0", 0, wisdomLog.entryCount())
        addTestEntry()
        assertEquals("Size should be 1 after adding one entry", 1, wisdomLog.entryCount())
        addTestEntry(title = "Second Wisdom")
        assertEquals("Size should be 2 after adding two entries", 2, wisdomLog.entryCount())
    }

    @Test
    fun getEntries_returnsAddedEntries() {
        addTestEntry(title = "First Wisdom", lesson = "First lesson")
        addTestEntry(title = "Second Wisdom", lesson = "Second lesson")

        val entries = wisdomLog.getRecentEntries(10)
        assertEquals("Should return 2 entries", 2, entries.size)
        assertEquals("First entry title should match", "First Wisdom", entries[0].title)
        assertEquals("Second entry title should match", "Second Wisdom", entries[1].title)
    }

    @Test
    fun searchByTheme_findsMatchingEntries() {
        addTestEntry(title = "Trust and Safety", lesson = "Trust must be earned through consistent actions")
        addTestEntry(title = "Patience", lesson = "Patience is a virtue in learning")
        addTestEntry(title = "Trust Again", lesson = "Building trust takes time and effort")

        val results = wisdomLog.searchLessons("trust")
        assertEquals("Should find 2 entries matching 'trust'", 2, results.size)
    }

    @Test
    fun searchByTheme_returnsEmptyForNoMatch() {
        addTestEntry(title = "Some Wisdom", lesson = "Some lesson about kindness")

        val results = wisdomLog.searchLessons("xyznonexistentkeyword")
        assertTrue("Search should return empty for non-matching keyword", results.isEmpty())
    }

    @Test
    fun emptyLog_hasSizeZero() {
        assertEquals("Fresh wisdom log should have size 0", 0, wisdomLog.entryCount())
        assertTrue("getAllEntries on empty log should return empty list", wisdomLog.getAllEntries().isEmpty())
    }
}
