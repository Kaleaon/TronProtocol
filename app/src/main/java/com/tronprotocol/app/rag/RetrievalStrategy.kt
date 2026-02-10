package com.tronprotocol.app.rag

/**
 * Retrieval strategies for RAG system
 *
 * Inspired by landseek's multiple retrieval approaches, ToolNeuron's RAG system,
 * and MiniRAG's topology-enhanced retrieval (arXiv:2501.06713)
 */
enum class RetrievalStrategy {
    /** Embedding-based semantic similarity (ToolNeuron approach) */
    SEMANTIC,

    /** TF-IDF keyword matching */
    KEYWORD,

    /** Combination of semantic and keyword (hybrid) */
    HYBRID,

    /** Prioritize recent additions (recency-based) */
    RECENCY,

    /** Semantic similarity with time decay - balances relevance and freshness */
    RELEVANCE_DECAY,

    /**
     * MemRL: Two-phase retrieval with Q-value ranking (arXiv:2601.03192)
     * Self-evolving memory system from landseek
     */
    MEMRL,

    /**
     * Graph-based topology retrieval (MiniRAG-inspired, arXiv:2501.06713)
     * Uses heterogeneous knowledge graph for entity-aware retrieval.
     * Traverses entity-chunk relationships and uses path scoring
     * rather than relying solely on embedding quality.
     */
    GRAPH
}
