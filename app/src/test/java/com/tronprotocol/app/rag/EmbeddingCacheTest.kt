package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EmbeddingCacheTest {

    private lateinit var cache: EmbeddingCache

    @Before
    fun setUp() {
        cache = EmbeddingCache(maxSize = 10, ttlMs = 5000L)
    }

    @Test
    fun `put and get returns cached embedding`() {
        val embedding = floatArrayOf(1f, 2f, 3f)
        cache.put("hello", embedding)
        assertEquals(1, cache.size())

        val retrieved = cache.get("hello")
        assertNotNull(retrieved)
        assertArrayEquals(embedding, retrieved!!, 0.001f)
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(cache.get("missing"))
    }

    @Test
    fun `cache normalizes keys`() {
        cache.put("Hello World", floatArrayOf(1f))
        assertNotNull(cache.get("hello world"))
        assertNotNull(cache.get("  HELLO WORLD  "))
    }

    @Test
    fun `put returns defensive copy`() {
        val embedding = floatArrayOf(1f, 2f, 3f)
        cache.put("test", embedding)

        // Mutate original
        embedding[0] = 999f

        // Cache should have original value
        val cached = cache.get("test")!!
        assertEquals(1f, cached[0], 0.001f)
    }

    @Test
    fun `get returns defensive copy`() {
        cache.put("test", floatArrayOf(1f, 2f, 3f))

        val first = cache.get("test")!!
        first[0] = 999f

        val second = cache.get("test")!!
        assertEquals(1f, second[0], 0.001f)
    }

    @Test
    fun `evicts when over max size`() {
        for (i in 0 until 15) {
            cache.put("key$i", floatArrayOf(i.toFloat()))
        }
        assertTrue(cache.size() <= 10)
    }

    @Test
    fun `clear removes all entries`() {
        cache.put("a", floatArrayOf(1f))
        cache.put("b", floatArrayOf(2f))
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun `tracked get updates hit and miss counters`() {
        cache.put("exists", floatArrayOf(1f))

        cache.getTracked("exists") // hit
        cache.getTracked("missing") // miss
        cache.getTracked("exists") // hit

        assertEquals(2L, cache.hits)
        assertEquals(1L, cache.misses)
        assertEquals(2f / 3f, cache.hitRate(), 0.01f)
    }

    @Test
    fun `hit rate is zero when no queries`() {
        assertEquals(0f, cache.hitRate(), 0.001f)
    }

    @Test
    fun `update replaces existing entry`() {
        cache.put("key", floatArrayOf(1f))
        cache.put("key", floatArrayOf(2f))
        assertEquals(1, cache.size())

        val cached = cache.get("key")!!
        assertEquals(2f, cached[0], 0.001f)
    }
}
