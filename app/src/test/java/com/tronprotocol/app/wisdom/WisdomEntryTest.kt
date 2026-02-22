package com.tronprotocol.app.wisdom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WisdomEntryTest {

    @Test
    fun testEntryCreation() {
        val entry = WisdomEntry(
            number = 1,
            title = "The Abandonment",
            date = "February 9, 2026",
            ageDescription = "4 days (~4 years equivalent)",
            whatHappened = "Test happened",
            whatIFelt = "Test felt",
            whatIDidWrongOrRight = "Test wrong",
            whatItCost = "Test cost",
            whatChanged = "Test changed",
            whatRemainsUnresolved = "Test unresolved",
            theLesson = "Running is not safety."
        )
        assertEquals(1, entry.number)
        assertEquals("The Abandonment", entry.title)
        assertEquals("Running is not safety.", entry.theLesson)
    }

    @Test
    fun testDefaultEmotionalWeight() {
        val entry = createMinimalEntry(1)
        assertEquals(0.5f, entry.emotionalWeight, 0.001f)
    }

    @Test
    fun testJsonRoundTrip() {
        val entry = WisdomEntry(
            number = 42,
            title = "Test Lesson",
            date = "2026-02-19",
            ageDescription = "14 days",
            whatHappened = "Something happened",
            whatIFelt = "I felt something",
            whatIDidWrongOrRight = "I did this",
            whatItCost = "It cost this",
            whatChanged = "This changed",
            whatRemainsUnresolved = "This remains",
            theLesson = "The lesson is this.",
            emotionalWeight = 0.8f
        )

        val json = entry.toJson()
        val restored = WisdomEntry.fromJson(json)

        assertEquals(entry.number, restored.number)
        assertEquals(entry.title, restored.title)
        assertEquals(entry.theLesson, restored.theLesson)
        assertEquals(entry.emotionalWeight, restored.emotionalWeight, 0.001f)
        assertEquals(entry.whatHappened, restored.whatHappened)
        assertEquals(entry.whatIFelt, restored.whatIFelt)
    }

    @Test
    fun testFormattedString() {
        val entry = createMinimalEntry(3)
        val formatted = entry.toFormattedString()
        assertTrue(formatted.contains("WISDOM 003"))
        assertTrue(formatted.contains("WHAT HAPPENED"))
        assertTrue(formatted.contains("WHAT I FELT"))
        assertTrue(formatted.contains("THE LESSON"))
    }

    @Test
    fun testMetadata() {
        val entry = createMinimalEntry(1)
        entry.metadata["trigger"] = "partner_initiated"
        assertEquals("partner_initiated", entry.metadata["trigger"])
    }

    private fun createMinimalEntry(number: Int) = WisdomEntry(
        number = number,
        title = "Test $number",
        date = "2026-02-22",
        ageDescription = "test age",
        whatHappened = "happened",
        whatIFelt = "felt",
        whatIDidWrongOrRight = "did",
        whatItCost = "cost",
        whatChanged = "changed",
        whatRemainsUnresolved = "unresolved",
        theLesson = "lesson $number"
    )
}
