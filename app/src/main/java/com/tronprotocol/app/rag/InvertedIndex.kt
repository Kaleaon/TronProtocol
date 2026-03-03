package com.tronprotocol.app.rag

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TF-IDF inverted index for fast keyword retrieval over RAG chunks.
 *
 * Inspired by "Build Your Own Search Engine" (vector space indexing).
 * Provides O(1) token lookup instead of O(n) linear scan across all chunks.
 *
 * The index maintains:
 * - An inverted posting list: token -> list of (chunkId, termFrequency)
 * - Document frequency counts for IDF computation
 * - A document count for normalization
 */
class InvertedIndex {

    /** A single posting: a chunk that contains the token, with its term frequency. */
    data class Posting(val chunkId: String, val tf: Float)

    /** Scored result from the index. */
    data class ScoredHit(val chunkId: String, val tfidfScore: Float)

    // token -> list of postings
    private val index = mutableMapOf<String, MutableList<Posting>>()
    // token -> number of documents containing it
    private val documentFrequency = mutableMapOf<String, Int>()
    // chunkId -> set of tokens (for removal support)
    private val documentTokens = mutableMapOf<String, Set<String>>()
    // total document count
    private var documentCount = 0

    /**
     * Add a document (chunk) to the index.
     *
     * @param chunkId Unique identifier for the chunk
     * @param content Text content of the chunk
     */
    fun add(chunkId: String, content: String) {
        val tokens = tokenize(content)
        if (tokens.isEmpty()) return

        // Remove old entry if updating
        if (documentTokens.containsKey(chunkId)) {
            remove(chunkId)
        }

        documentCount++

        // Count term frequencies
        val termFreqs = mutableMapOf<String, Int>()
        for (token in tokens) {
            termFreqs[token] = (termFreqs[token] ?: 0) + 1
        }

        val uniqueTokens = termFreqs.keys.toSet()
        documentTokens[chunkId] = uniqueTokens

        // Compute normalized TF and build postings
        val maxTf = termFreqs.values.max()
        for ((token, count) in termFreqs) {
            val normalizedTf = 0.5f + 0.5f * (count.toFloat() / maxTf)
            val postings = index.getOrPut(token) { mutableListOf() }
            postings.add(Posting(chunkId, normalizedTf))

            documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
        }
    }

    /**
     * Remove a document from the index.
     *
     * @param chunkId Chunk to remove
     * @return true if the chunk was found and removed
     */
    fun remove(chunkId: String): Boolean {
        val tokens = documentTokens.remove(chunkId) ?: return false
        documentCount--

        for (token in tokens) {
            index[token]?.removeAll { it.chunkId == chunkId }
            if (index[token]?.isEmpty() == true) {
                index.remove(token)
            }
            val df = documentFrequency[token]
            if (df != null) {
                if (df <= 1) documentFrequency.remove(token)
                else documentFrequency[token] = df - 1
            }
        }
        return true
    }

    /**
     * Search the index for chunks matching the query.
     * Returns results ranked by TF-IDF cosine similarity.
     *
     * @param query Search query string
     * @param topK Maximum number of results to return
     * @return Ranked list of chunk IDs with TF-IDF scores
     */
    fun search(query: String, topK: Int): List<ScoredHit> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || documentCount == 0) return emptyList()

        // Build query TF vector
        val queryTermFreqs = mutableMapOf<String, Int>()
        for (token in queryTokens) {
            queryTermFreqs[token] = (queryTermFreqs[token] ?: 0) + 1
        }

        // Compute query TF-IDF weights
        val queryWeights = mutableMapOf<String, Float>()
        for ((token, count) in queryTermFreqs) {
            val tf = count.toFloat() / queryTokens.size
            val df = documentFrequency[token] ?: 0
            if (df == 0) continue
            val idf = ln((documentCount.toFloat() + 1) / (df + 1)) + 1.0f
            queryWeights[token] = tf * idf
        }

        if (queryWeights.isEmpty()) return emptyList()

        // Accumulate document scores using only the posting lists we need (sparse dot product)
        val docScores = mutableMapOf<String, Float>()
        for ((token, queryWeight) in queryWeights) {
            val postings = index[token] ?: continue
            val df = documentFrequency[token] ?: continue
            val idf = ln((documentCount.toFloat() + 1) / (df + 1)) + 1.0f

            for (posting in postings) {
                val docTfIdf = posting.tf * idf
                docScores[posting.chunkId] = (docScores[posting.chunkId] ?: 0f) + queryWeight * docTfIdf
            }
        }

        // Normalize scores to [0, 1] range
        val maxScore = docScores.values.maxOrNull() ?: return emptyList()
        if (maxScore <= 0f) return emptyList()

        return docScores.entries
            .map { ScoredHit(it.key, it.value / maxScore) }
            .sortedByDescending { it.tfidfScore }
            .take(topK)
    }

    /** Number of indexed documents. */
    fun size(): Int = documentCount

    /** Number of unique tokens in the index. */
    fun vocabularySize(): Int = index.size

    /** Clear the entire index. */
    fun clear() {
        index.clear()
        documentFrequency.clear()
        documentTokens.clear()
        documentCount = 0
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "it", "in", "on", "to", "of", "and",
            "or", "for", "at", "by", "be", "am", "are", "was", "were",
            "been", "has", "have", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "can", "this",
            "that", "these", "those", "i", "me", "my", "we", "our",
            "you", "your", "he", "she", "they", "them", "his", "her",
            "its", "not", "no", "so", "if", "but", "with", "from", "as"
        )

        /**
         * Tokenize text into normalized tokens, filtering stop words.
         */
        fun tokenize(text: String): List<String> =
            text.lowercase()
                .replace("[^a-z0-9\\s]".toRegex(), " ")
                .split("\\s+".toRegex())
                .filter { it.length > 1 && it !in STOP_WORDS }
    }
}
