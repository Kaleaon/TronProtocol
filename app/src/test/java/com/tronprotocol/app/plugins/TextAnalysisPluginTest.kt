package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TextAnalysisPluginTest {

    private lateinit var plugin: TextAnalysisPlugin

    @Before
    fun setUp() {
        plugin = TextAnalysisPlugin()
    }

    // --- Word count ---

    @Test
    fun testWordCount() {
        val result = plugin.execute("word_count|hello world foo bar")
        assertTrue(result.isSuccess)
        assertEquals("4", result.data)
    }

    @Test
    fun testWordCountEmpty() {
        val result = plugin.execute("word_count|   ")
        assertTrue(result.isSuccess)
        assertEquals("0", result.data)
    }

    // --- Character count ---

    @Test
    fun testCharCount() {
        val result = plugin.execute("char_count|hello")
        assertTrue(result.isSuccess)
        assertEquals("5", result.data)
    }

    // --- Case transformations ---

    @Test
    fun testUppercase() {
        val result = plugin.execute("uppercase|hello world")
        assertTrue(result.isSuccess)
        assertEquals("HELLO WORLD", result.data)
    }

    @Test
    fun testLowercase() {
        val result = plugin.execute("lowercase|HELLO WORLD")
        assertTrue(result.isSuccess)
        assertEquals("hello world", result.data)
    }

    @Test
    fun testTitleCase() {
        val result = plugin.execute("title|hello world foo")
        assertTrue(result.isSuccess)
        assertEquals("Hello World Foo", result.data)
    }

    // --- Text operations ---

    @Test
    fun testReverse() {
        val result = plugin.execute("reverse|hello")
        assertTrue(result.isSuccess)
        assertEquals("olleh", result.data)
    }

    @Test
    fun testTrim() {
        val result = plugin.execute("trim|  hello world  ")
        assertTrue(result.isSuccess)
        assertEquals("hello world", result.data)
    }

    // --- URL extraction ---

    @Test
    fun testExtractUrls() {
        val result = plugin.execute("extract_urls|Visit https://example.com and http://test.org/path for info")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("https://example.com"))
        assertTrue(result.data!!.contains("http://test.org/path"))
        assertTrue(result.data!!.contains("Found 2 URL(s)"))
    }

    @Test
    fun testExtractUrlsNone() {
        val result = plugin.execute("extract_urls|No links here")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("No URLs found"))
    }

    // --- Email extraction ---

    @Test
    fun testExtractEmails() {
        val result = plugin.execute("extract_emails|Contact user@example.com or admin@test.org")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("user@example.com"))
        assertTrue(result.data!!.contains("admin@test.org"))
        assertTrue(result.data!!.contains("Found 2 email(s)"))
    }

    @Test
    fun testExtractEmailsNone() {
        val result = plugin.execute("extract_emails|No emails here")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("No email addresses found"))
    }

    // --- Stats ---

    @Test
    fun testStats() {
        val result = plugin.execute("stats|Hello world. How are you?")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Words: 5"))
        assertTrue(result.data!!.contains("Sentences: 2"))
        assertTrue(result.data!!.contains("Characters:"))
    }

    // --- Sentences ---

    @Test
    fun testSentences() {
        val result = plugin.execute("sentences|First sentence. Second sentence! Third?")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Found 3 sentence(s)"))
        assertTrue(result.data!!.contains("First sentence."))
        assertTrue(result.data!!.contains("Second sentence!"))
    }

    // --- Readability ---

    @Test
    fun testReadability() {
        val result = plugin.execute("readability|The cat sat on the mat. It was a good day.")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Flesch-Kincaid"))
        assertTrue(result.data!!.contains("Level:"))
    }

    @Test
    fun testReadabilityEmpty() {
        val result = plugin.execute("readability|   ")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Cannot calculate"))
    }

    // --- Word frequency ---

    @Test
    fun testFrequency() {
        val result = plugin.execute("frequency|the cat the dog the bird cat")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("the: 3"))
        assertTrue(result.data!!.contains("cat: 2"))
    }

    @Test
    fun testFrequencyEmpty() {
        val result = plugin.execute("frequency|   ")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("No words"))
    }

    // --- Error cases ---

    @Test
    fun testMissingPipe() {
        val result = plugin.execute("uppercase")
        assertFalse(result.isSuccess)
    }

    @Test
    fun testUnknownCommand() {
        val result = plugin.execute("foobar|text")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Unknown command"))
    }

    // --- Plugin interface ---

    @Test
    fun testPluginId() {
        assertEquals("text_analysis", plugin.id)
    }

    @Test
    fun testPluginEnabled() {
        assertTrue(plugin.isEnabled)
    }
}
