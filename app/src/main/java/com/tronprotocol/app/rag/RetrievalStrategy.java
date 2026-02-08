package com.tronprotocol.app.rag;

/**
 * Retrieval strategies for RAG system
 * 
 * Inspired by landseek's multiple retrieval approaches and ToolNeuron's RAG system
 */
public enum RetrievalStrategy {
    /**
     * Embedding-based semantic similarity (ToolNeuron approach)
     */
    SEMANTIC,
    
    /**
     * TF-IDF keyword matching
     */
    KEYWORD,
    
    /**
     * Combination of semantic and keyword (hybrid)
     */
    HYBRID,
    
    /**
     * Prioritize recent additions (recency-based)
     */
    RECENCY,
    
    /**
     * Semantic similarity with time decay
     */
    RELEVANCE_DECAY,
    
    /**
     * MemRL: Two-phase retrieval with Q-value ranking (arXiv:2601.03192)
     * Self-evolving memory system from landseek
     */
    MEMRL
}
