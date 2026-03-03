package com.tronprotocol.app.rag

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Locality-Sensitive Hashing (LSH) index for approximate nearest neighbor search.
 *
 * Inspired by "Build Your Own Neural Network" (BYOX) — vector space search.
 * Provides sub-linear O(L * bucket_size) lookup instead of O(n) brute-force
 * cosine similarity over all chunk embeddings.
 *
 * Uses random hyperplane hashing: each hash function projects the vector onto
 * a random normal vector and takes the sign. Vectors that are close in cosine
 * distance tend to land in the same hash bucket.
 *
 * @param dimension Embedding vector dimension
 * @param numTables Number of hash tables (more = higher recall, more memory)
 * @param numBits Number of hash bits per table (more = more selective buckets)
 */
class ANNIndex(
    private val dimension: Int,
    private val numTables: Int = DEFAULT_NUM_TABLES,
    private val numBits: Int = DEFAULT_NUM_BITS
) {
    /** A stored vector with its ID. */
    data class IndexEntry(val id: String, val vector: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IndexEntry) return false
            return id == other.id
        }
        override fun hashCode(): Int = id.hashCode()
    }

    /** Search result with distance score. */
    data class Neighbor(val id: String, val similarity: Float)

    // Random hyperplanes for each (table, bit)
    private val hyperplanes: Array<Array<FloatArray>> = Array(numTables) {
        Array(numBits) { randomUnitVector(dimension) }
    }

    // hash tables: table_index -> hash_bucket -> list of entries
    private val tables: Array<MutableMap<Int, MutableList<IndexEntry>>> =
        Array(numTables) { mutableMapOf() }

    // All entries for deduplication
    private val allEntries = mutableMapOf<String, FloatArray>()

    /**
     * Add a vector to the index.
     */
    fun add(id: String, vector: FloatArray) {
        require(vector.size == dimension) { "Vector dimension mismatch: expected $dimension, got ${vector.size}" }

        // Remove old entry if updating
        if (id in allEntries) {
            remove(id)
        }

        val normalized = l2Normalize(vector)
        allEntries[id] = normalized
        val entry = IndexEntry(id, normalized)

        for (t in 0 until numTables) {
            val hash = computeHash(normalized, t)
            tables[t].getOrPut(hash) { mutableListOf() }.add(entry)
        }
    }

    /**
     * Remove a vector from the index.
     */
    fun remove(id: String): Boolean {
        val vector = allEntries.remove(id) ?: return false
        for (t in 0 until numTables) {
            val hash = computeHash(vector, t)
            tables[t][hash]?.removeAll { it.id == id }
        }
        return true
    }

    /**
     * Search for approximate nearest neighbors.
     *
     * @param query Query vector
     * @param topK Number of results to return
     * @return List of neighbors sorted by cosine similarity (descending)
     */
    fun search(query: FloatArray, topK: Int): List<Neighbor> {
        require(query.size == dimension) { "Query dimension mismatch: expected $dimension, got ${query.size}" }

        val normalized = l2Normalize(query)

        // Collect candidate IDs from all tables
        val candidateIds = mutableSetOf<String>()
        for (t in 0 until numTables) {
            val hash = computeHash(normalized, t)
            tables[t][hash]?.forEach { candidateIds.add(it.id) }
        }

        // If too few candidates, fall back to brute force
        if (candidateIds.size < topK && allEntries.size <= BRUTE_FORCE_THRESHOLD) {
            return bruteForceFallback(normalized, topK)
        }

        // Score candidates by exact cosine similarity
        return candidateIds.mapNotNull { id ->
            val vec = allEntries[id] ?: return@mapNotNull null
            val sim = dotProduct(normalized, vec)
            Neighbor(id, sim)
        }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    /**
     * Brute-force search over all entries. Used as fallback or for small datasets.
     */
    private fun bruteForceFallback(query: FloatArray, topK: Int): List<Neighbor> =
        allEntries.map { (id, vec) ->
            Neighbor(id, dotProduct(query, vec))
        }
            .sortedByDescending { it.similarity }
            .take(topK)

    /** Number of indexed vectors. */
    fun size(): Int = allEntries.size

    /** Clear the entire index. */
    fun clear() {
        allEntries.clear()
        for (table in tables) table.clear()
    }

    // --- Hash computation ---

    private fun computeHash(vector: FloatArray, tableIndex: Int): Int {
        var hash = 0
        for (b in 0 until numBits) {
            val dot = dotProduct(vector, hyperplanes[tableIndex][b])
            if (dot >= 0) hash = hash or (1 shl b)
        }
        return hash
    }

    companion object {
        private const val DEFAULT_NUM_TABLES = 4
        private const val DEFAULT_NUM_BITS = 8
        private const val BRUTE_FORCE_THRESHOLD = 200

        private val rng = Random(42)

        fun randomUnitVector(dim: Int): FloatArray {
            val v = FloatArray(dim) { rng.nextFloat() * 2 - 1 }
            return l2Normalize(v)
        }

        fun l2Normalize(vector: FloatArray): FloatArray {
            var norm = 0f
            for (v in vector) norm += v * v
            norm = sqrt(norm)
            if (norm <= 0f) return vector.copyOf()
            return FloatArray(vector.size) { vector[it] / norm }
        }

        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
