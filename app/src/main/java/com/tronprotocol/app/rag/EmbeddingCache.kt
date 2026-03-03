package com.tronprotocol.app.rag

import java.util.concurrent.ConcurrentHashMap

/**
 * LRU embedding cache with TTL expiry for query embeddings.
 *
 * Avoids recomputing embeddings for repeated or recent queries.
 * Thread-safe via ConcurrentHashMap.
 *
 * @param maxSize Maximum number of cached embeddings
 * @param ttlMs Time-to-live in milliseconds (default: 5 minutes)
 */
class EmbeddingCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private data class CacheEntry(
        val embedding: FloatArray,
        val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CacheEntry) return false
            return embedding.contentEquals(other.embedding) && timestamp == other.timestamp
        }
        override fun hashCode(): Int = embedding.contentHashCode() * 31 + timestamp.hashCode()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    // Track insertion order for LRU eviction
    private val accessOrder = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

    /**
     * Get a cached embedding for the given text.
     *
     * @return The cached embedding, or null if not found or expired
     */
    fun get(text: String): FloatArray? {
        val key = normalizeKey(text)
        val entry = cache[key] ?: return null

        val now = System.currentTimeMillis()
        if (now - entry.timestamp > ttlMs) {
            cache.remove(key)
            synchronized(accessOrder) { accessOrder.remove(key) }
            return null
        }

        synchronized(accessOrder) { accessOrder[key] = now }
        return entry.embedding.copyOf()
    }

    /**
     * Cache an embedding for the given text.
     */
    fun put(text: String, embedding: FloatArray) {
        val key = normalizeKey(text)
        val now = System.currentTimeMillis()

        evictIfNeeded()

        cache[key] = CacheEntry(embedding.copyOf(), now)
        synchronized(accessOrder) { accessOrder[key] = now }
    }

    /**
     * Number of entries currently in the cache.
     */
    fun size(): Int = cache.size

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.clear()
        synchronized(accessOrder) { accessOrder.clear() }
    }

    /**
     * Cache hit rate statistics.
     */
    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set

    fun hitRate(): Float =
        if (hits + misses == 0L) 0f
        else hits.toFloat() / (hits + misses)

    /**
     * Get with hit/miss tracking.
     */
    fun getTracked(text: String): FloatArray? {
        val result = get(text)
        if (result != null) hits++ else misses++
        return result
    }

    private fun evictIfNeeded() {
        if (cache.size < maxSize) return

        // Remove expired entries first
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { now - it.value.timestamp > ttlMs }
        for (entry in expired) {
            cache.remove(entry.key)
            synchronized(accessOrder) { accessOrder.remove(entry.key) }
        }

        // If still over capacity, evict LRU
        if (cache.size >= maxSize) {
            synchronized(accessOrder) {
                val iterator = accessOrder.entries.iterator()
                while (iterator.hasNext() && cache.size >= maxSize) {
                    val entry = iterator.next()
                    cache.remove(entry.key)
                    iterator.remove()
                }
            }
        }
    }

    private fun normalizeKey(text: String): String =
        text.lowercase().trim()

    companion object {
        private const val DEFAULT_MAX_SIZE = 256
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
