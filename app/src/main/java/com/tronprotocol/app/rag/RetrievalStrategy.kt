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
    GRAPH,

    /**
     * Frontier-aware retrieval (Frontier Dynamics STLE-enhanced).
     * Combines semantic similarity with STLE accessibility scoring to
     * prefer in-distribution (high mu_x) chunks and deprioritise
     * out-of-distribution results. Uses the Set Theoretic Learning
     * Environment's complementary fuzzy sets (mu_x + mu_y = 1).
     *
     * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
     */
    FRONTIER_AWARE,

    /**
     * Neural Temporal Stack (NTS) cascade retrieval.
     *
     * Implements the layered memory cascade described in the TronProtocol
     * architecture docs: sensory → working → episodic → semantic.
     * Retrieval blends semantic relevance with stage durability, recency,
     * and learned MemRL utility to better preserve identity over long-running
     * sessions.
     */
    NTS_CASCADE
}
