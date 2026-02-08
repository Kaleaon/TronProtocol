package com.tronprotocol.app.rag;

import java.util.HashMap;
import java.util.Map;

/**
 * A chunk of text with metadata and embeddings
 * 
 * Combines features from:
 * - landseek's TextChunk with MemRL Q-values
 * - ToolNeuron's chunk management
 */
public class TextChunk {
    private String chunkId;
    private String content;
    private String source;  // Source document or conversation
    private String sourceType;  // "document", "conversation", "memory", "knowledge"
    private String timestamp;
    private int tokenCount;
    private Map<String, Object> metadata;
    private float[] embedding;
    
    // MemRL fields for self-evolving memory (arXiv:2601.03192)
    private float qValue;  // Q-value for utility-based ranking (range 0-1)
    private int retrievalCount;  // Number of times retrieved
    private int successCount;  // Number of successful retrievals
    
    public TextChunk(String chunkId, String content, String source, String sourceType,
                     String timestamp, int tokenCount) {
        this.chunkId = chunkId;
        this.content = content;
        this.source = source;
        this.sourceType = sourceType;
        this.timestamp = timestamp;
        this.tokenCount = tokenCount;
        this.metadata = new HashMap<>();
        this.qValue = 0.5f;  // Initial Q-value
        this.retrievalCount = 0;
        this.successCount = 0;
    }
    
    // Getters
    public String getChunkId() { return chunkId; }
    public String getContent() { return content; }
    public String getSource() { return source; }
    public String getSourceType() { return sourceType; }
    public String getTimestamp() { return timestamp; }
    public int getTokenCount() { return tokenCount; }
    public Map<String, Object> getMetadata() { return metadata; }
    public float[] getEmbedding() { return embedding; }
    public float getQValue() { return qValue; }
    public int getRetrievalCount() { return retrievalCount; }
    public int getSuccessCount() { return successCount; }
    
    // Setters
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    /**
     * Update Q-value based on feedback (MemRL learning)
     * @param success Whether the retrieval was successful
     * @param learningRate Learning rate for Q-value update (default 0.1)
     */
    public void updateQValue(boolean success, float learningRate) {
        retrievalCount++;
        if (success) {
            successCount++;
        }
        
        // Q-learning update: Q(s,a) += α * (reward - Q(s,a))
        float reward = success ? 1.0f : 0.0f;
        qValue += learningRate * (reward - qValue);
        
        // Clamp to [0, 1]
        qValue = Math.max(0.0f, Math.min(1.0f, qValue));
    }
    
    /**
     * Get success rate for this chunk
     */
    public float getSuccessRate() {
        return retrievalCount > 0 ? (float) successCount / retrievalCount : 0.0f;
    }
    
    /**
     * Add metadata field
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    @Override
    public String toString() {
        return "TextChunk{" +
                "id='" + chunkId + '\'' +
                ", source='" + source + '\'' +
                ", tokens=" + tokenCount +
                ", qValue=" + String.format("%.3f", qValue) +
                ", successRate=" + String.format("%.1f%%", getSuccessRate() * 100) +
                '}';
    }
}
