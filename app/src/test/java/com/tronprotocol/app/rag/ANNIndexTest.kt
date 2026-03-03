package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class ANNIndexTest {

    private lateinit var index: ANNIndex

    @Before
    fun setUp() {
        index = ANNIndex(dimension = 8, numTables = 4, numBits = 4)
    }

    @Test
    fun `empty index returns no results`() {
        val query = FloatArray(8) { 1f }
        val results = index.search(query, 5)
        assertTrue(results.isEmpty())
        assertEquals(0, index.size())
    }

    @Test
    fun `add and search single vector`() {
        val vec = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        index.add("v1", vec)
        assertEquals(1, index.size())

        val results = index.search(vec, 5)
        assertTrue(results.isNotEmpty())
        assertEquals("v1", results[0].id)
        assertTrue(results[0].similarity > 0.99f)
    }

    @Test
    fun `similar vectors rank higher than dissimilar ones`() {
        val queryDir = floatArrayOf(1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
        val similar = floatArrayOf(1f, 0.9f, 0.1f, 0f, 0f, 0f, 0f, 0f)
        val dissimilar = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f)

        index.add("similar", similar)
        index.add("dissimilar", dissimilar)

        val results = index.search(queryDir, 10)
        assertTrue(results.size >= 2)
        assertEquals("similar", results[0].id)
    }

    @Test
    fun `remove vector from index`() {
        index.add("v1", FloatArray(8) { 1f })
        index.add("v2", FloatArray(8) { 0.5f })
        assertEquals(2, index.size())

        val removed = index.remove("v1")
        assertTrue(removed)
        assertEquals(1, index.size())
    }

    @Test
    fun `remove non-existent returns false`() {
        assertFalse(index.remove("nonexistent"))
    }

    @Test
    fun `update replaces old vector`() {
        val oldVec = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val newVec = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        val query = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)

        index.add("v1", oldVec)
        index.add("v1", newVec) // update
        assertEquals(1, index.size())

        val results = index.search(query, 1)
        assertTrue(results.isNotEmpty())
        assertEquals("v1", results[0].id)
        assertTrue(results[0].similarity > 0.9f) // should be close to 1.0
    }

    @Test
    fun `topK limits results`() {
        for (i in 1..20) {
            val vec = FloatArray(8) { if (it == i % 8) 1f else 0f }
            index.add("v$i", vec)
        }
        val query = FloatArray(8) { 1f }
        val results = index.search(query, 5)
        assertTrue(results.size <= 5)
    }

    @Test
    fun `clear removes all entries`() {
        index.add("v1", FloatArray(8) { 1f })
        index.add("v2", FloatArray(8) { 0.5f })
        index.clear()
        assertEquals(0, index.size())
    }

    @Test
    fun `l2Normalize produces unit vector`() {
        val vec = floatArrayOf(3f, 4f, 0f, 0f, 0f, 0f, 0f, 0f)
        val normalized = ANNIndex.l2Normalize(vec)
        var normSq = 0f
        for (v in normalized) normSq += v * v
        assertEquals(1.0f, sqrt(normSq), 0.001f)
    }

    @Test
    fun `dotProduct computes correctly`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, ANNIndex.dotProduct(a, b), 0.001f)

        val c = floatArrayOf(1f, 2f, 3f)
        val d = floatArrayOf(4f, 5f, 6f)
        assertEquals(32f, ANNIndex.dotProduct(c, d), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `add with wrong dimension throws`() {
        index.add("bad", FloatArray(3) { 1f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `search with wrong dimension throws`() {
        index.search(FloatArray(3) { 1f }, 5)
    }

    @Test
    fun `recall is reasonable for moderate dataset`() {
        // Build a small dataset and verify ANN finds at least some of the true nearest neighbors
        val dim = 8
        val annIndex = ANNIndex(dim, numTables = 6, numBits = 6)
        val vectors = mutableMapOf<String, FloatArray>()

        for (i in 0 until 100) {
            val vec = FloatArray(dim) { ((i * 7 + it * 13) % 100).toFloat() / 100f }
            vectors["v$i"] = ANNIndex.l2Normalize(vec)
            annIndex.add("v$i", vec)
        }

        val query = FloatArray(dim) { 0.5f }
        val queryNorm = ANNIndex.l2Normalize(query)

        // Brute force top 10
        val bruteForce = vectors.entries
            .map { it.key to ANNIndex.dotProduct(queryNorm, it.value) }
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }
            .toSet()

        // ANN top 10
        val annResults = annIndex.search(query, 10).map { it.id }.toSet()

        // At least 50% recall (LSH is approximate)
        val overlap = annResults.intersect(bruteForce).size
        assertTrue("Recall should be reasonable, got $overlap/10", overlap >= 5)
    }
}
