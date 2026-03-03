package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InvertedIndexTest {

    private lateinit var index: InvertedIndex

    @Before
    fun setUp() {
        index = InvertedIndex()
    }

    @Test
    fun `empty index returns no results`() {
        val results = index.search("hello world", 10)
        assertTrue(results.isEmpty())
        assertEquals(0, index.size())
    }

    @Test
    fun `add and search single document`() {
        index.add("chunk1", "Kotlin is a modern programming language")
        assertEquals(1, index.size())

        val results = index.search("kotlin programming", 5)
        assertTrue(results.isNotEmpty())
        assertEquals("chunk1", results[0].chunkId)
        assertTrue(results[0].tfidfScore > 0f)
    }

    @Test
    fun `search ranks more relevant documents higher`() {
        index.add("chunk1", "The weather today is sunny and warm")
        index.add("chunk2", "Kotlin is a programming language for Android development")
        index.add("chunk3", "Android development with Kotlin is productive and enjoyable")

        val results = index.search("kotlin android", 10)
        assertTrue(results.size >= 2)

        // chunk2 and chunk3 both mention kotlin and android, chunk1 does not
        val ids = results.map { it.chunkId }
        assertTrue("chunk2" in ids)
        assertTrue("chunk3" in ids)
        assertFalse("chunk1" in ids)
    }

    @Test
    fun `remove document from index`() {
        index.add("chunk1", "Hello world")
        index.add("chunk2", "Goodbye world")
        assertEquals(2, index.size())

        val removed = index.remove("chunk1")
        assertTrue(removed)
        assertEquals(1, index.size())

        val results = index.search("hello", 5)
        assertTrue(results.none { it.chunkId == "chunk1" })
    }

    @Test
    fun `remove non-existent document returns false`() {
        assertFalse(index.remove("nonexistent"))
    }

    @Test
    fun `TF-IDF boosts rare terms`() {
        // "machine" appears in 1 doc, "learning" appears in 3
        index.add("chunk1", "machine learning algorithms and neural networks")
        index.add("chunk2", "deep learning with tensorflow framework")
        index.add("chunk3", "supervised learning techniques overview")

        val results = index.search("machine learning", 10)
        assertTrue(results.isNotEmpty())
        // chunk1 should rank highest because "machine" is rare (IDF boost)
        assertEquals("chunk1", results[0].chunkId)
    }

    @Test
    fun `search with all stop words returns empty`() {
        index.add("chunk1", "This is a test document")
        val results = index.search("is a the", 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `clear removes all entries`() {
        index.add("chunk1", "First document")
        index.add("chunk2", "Second document")
        assertEquals(2, index.size())

        index.clear()
        assertEquals(0, index.size())
        assertEquals(0, index.vocabularySize())
        assertTrue(index.search("document", 5).isEmpty())
    }

    @Test
    fun `update existing document replaces old entry`() {
        index.add("chunk1", "Original content about cats")
        index.add("chunk1", "Updated content about dogs")
        assertEquals(1, index.size())

        val catResults = index.search("cats", 5)
        assertTrue(catResults.isEmpty())

        val dogResults = index.search("dogs", 5)
        assertTrue(dogResults.isNotEmpty())
        assertEquals("chunk1", dogResults[0].chunkId)
    }

    @Test
    fun `scores are normalized between 0 and 1`() {
        index.add("chunk1", "kotlin programming language android")
        index.add("chunk2", "java programming language desktop")
        index.add("chunk3", "python scripting language data science")

        val results = index.search("kotlin android programming", 10)
        for (result in results) {
            assertTrue("Score should be <= 1.0", result.tfidfScore <= 1.0f)
            assertTrue("Score should be > 0.0", result.tfidfScore > 0.0f)
        }
        // Top result should have score 1.0 (normalized max)
        assertEquals(1.0f, results[0].tfidfScore, 0.001f)
    }

    @Test
    fun `tokenize filters stop words and short tokens`() {
        val tokens = InvertedIndex.tokenize("The quick brown fox is a test")
        assertFalse("the" in tokens)
        assertFalse("is" in tokens)
        assertFalse("a" in tokens)
        assertTrue("quick" in tokens)
        assertTrue("brown" in tokens)
        assertTrue("fox" in tokens)
        assertTrue("test" in tokens)
    }

    @Test
    fun `topK limits result count`() {
        for (i in 1..20) {
            index.add("chunk$i", "common term unique_word_$i")
        }
        val results = index.search("common term", 5)
        assertTrue(results.size <= 5)
    }

    @Test
    fun `vocabulary size reflects indexed tokens`() {
        index.add("chunk1", "kotlin android")
        assertTrue(index.vocabularySize() >= 2)

        index.add("chunk2", "kotlin java")
        // "kotlin" already exists, "java" is new
        assertTrue(index.vocabularySize() >= 3)
    }
}
