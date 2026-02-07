package com.tronprotocol.app.rag;

/**
 * Result from a RAG retrieval query
 * 
 * Combines landseek and ToolNeuron retrieval patterns
 */
public class RetrievalResult {
    private TextChunk chunk;
    private float score;
    private RetrievalStrategy strategy;
    
    public RetrievalResult(TextChunk chunk, float score, RetrievalStrategy strategy) {
        this.chunk = chunk;
        this.score = score;
        this.strategy = strategy;
    }
    
    public TextChunk getChunk() {
        return chunk;
    }
    
    public float getScore() {
        return score;
    }
    
    public RetrievalStrategy getStrategy() {
        return strategy;
    }
    
    @Override
    public String toString() {
        return "RetrievalResult{" +
                "score=" + String.format("%.3f", score) +
                ", strategy=" + strategy +
                ", chunk=" + chunk +
                '}';
    }
}
