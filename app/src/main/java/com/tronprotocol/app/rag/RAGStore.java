package com.tronprotocol.app.rag;

import android.content.Context;
import android.util.Log;

import com.tronprotocol.app.security.SecureStorage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) Store
 * 
 * Combines features from:
 * - landseek's MemRL self-evolving memory (arXiv:2601.03192)
 * - ToolNeuron's RAG document intelligence
 * 
 * Provides:
 * - Per-AI isolated knowledge bases
 * - Multiple retrieval strategies
 * - Q-value learning for memory evolution
 * - Persistent storage with encryption
 */
public class RAGStore {
    private static final String TAG = "RAGStore";
    private static final int DEFAULT_TOP_K = 10;
    private static final float DEFAULT_LEARNING_RATE = 0.1f;
    private static final int MAX_CHUNK_SIZE = 512;  // tokens
    
    private final Context context;
    private final String aiId;
    private final SecureStorage storage;
    private final List<TextChunk> chunks;
    private final Map<String, TextChunk> chunkIndex;
    
    public RAGStore(Context context, String aiId) throws Exception {
        this.context = context;
        this.aiId = aiId;
        this.storage = new SecureStorage(context);
        this.chunks = new ArrayList<>();
        this.chunkIndex = new HashMap<>();
        
        loadChunks();
    }
    
    /**
     * Add a memory to the RAG store
     * @param content Memory content
     * @param importance Importance score (0.0 to 1.0)
     */
    public String addMemory(String content, float importance) throws Exception {
        return addChunk(content, "memory", "memory", Collections.singletonMap("importance", importance));
    }
    
    /**
     * Add knowledge to the RAG store
     * @param content Knowledge content
     * @param category Category of knowledge
     */
    public String addKnowledge(String content, String category) throws Exception {
        return addChunk(content, category, "knowledge", Collections.singletonMap("category", category));
    }
    
    /**
     * Add a chunk to the RAG store
     */
    public String addChunk(String content, String source, String sourceType, 
                          Map<String, Object> metadata) throws Exception {
        String chunkId = generateChunkId(content, source);
        String timestamp = String.valueOf(System.currentTimeMillis());
        int tokenCount = estimateTokens(content);
        
        TextChunk chunk = new TextChunk(chunkId, content, source, sourceType, timestamp, tokenCount);
        if (metadata != null) {
            chunk.setMetadata(new HashMap<>(metadata));
        }
        
        // Generate embedding (simplified TF-IDF based)
        chunk.setEmbedding(generateEmbedding(content));
        
        chunks.add(chunk);
        chunkIndex.put(chunkId, chunk);
        
        saveChunks();
        Log.d(TAG, "Added chunk: " + chunkId + " for AI: " + aiId);
        
        return chunkId;
    }
    
    /**
     * Retrieve relevant chunks using specified strategy
     */
    public List<RetrievalResult> retrieve(String query, RetrievalStrategy strategy, int topK) {
        List<RetrievalResult> results = new ArrayList<>();
        
        switch (strategy) {
            case SEMANTIC:
                results = retrieveSemantic(query, topK);
                break;
            case KEYWORD:
                results = retrieveKeyword(query, topK);
                break;
            case HYBRID:
                results = retrieveHybrid(query, topK);
                break;
            case RECENCY:
                results = retrieveRecency(query, topK);
                break;
            case MEMRL:
                results = retrieveMemRL(query, topK);
                break;
            default:
                results = retrieveSemantic(query, topK);
        }
        
        return results;
    }
    
    /**
     * MemRL: Two-phase retrieval with Q-value ranking (arXiv:2601.03192)
     * 
     * Phase 1: Semantic retrieval to get candidates
     * Phase 2: Re-rank by learned Q-values (utility)
     */
    private List<RetrievalResult> retrieveMemRL(String query, int topK) {
        // Phase 1: Semantic retrieval (get more candidates)
        List<RetrievalResult> semanticResults = retrieveSemantic(query, topK * 3);
        
        // Phase 2: Re-rank by Q-values
        List<RetrievalResult> reranked = new ArrayList<>();
        for (RetrievalResult result : semanticResults) {
            // Combine semantic score with Q-value
            float semanticScore = result.getScore();
            float qValue = result.getChunk().getQValue();
            
            // Weighted combination: 70% semantic, 30% learned Q-value
            float combinedScore = 0.7f * semanticScore + 0.3f * qValue;
            
            reranked.add(new RetrievalResult(result.getChunk(), combinedScore, RetrievalStrategy.MEMRL));
        }
        
        // Sort by combined score
        Collections.sort(reranked, (a, b) -> Float.compare(b.getScore(), a.getScore()));
        
        // Return top-K
        return reranked.subList(0, Math.min(topK, reranked.size()));
    }
    
    /**
     * Semantic retrieval using embeddings
     */
    private List<RetrievalResult> retrieveSemantic(String query, int topK) {
        float[] queryEmbedding = generateEmbedding(query);
        List<RetrievalResult> results = new ArrayList<>();
        
        for (TextChunk chunk : chunks) {
            if (chunk.getEmbedding() != null) {
                float similarity = cosineSimilarity(queryEmbedding, chunk.getEmbedding());
                results.add(new RetrievalResult(chunk, similarity, RetrievalStrategy.SEMANTIC));
            }
        }
        
        Collections.sort(results, (a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }
    
    /**
     * Keyword-based retrieval
     */
    private List<RetrievalResult> retrieveKeyword(String query, int topK) {
        String[] queryTokens = query.toLowerCase().split("\\s+");
        List<RetrievalResult> results = new ArrayList<>();
        
        for (TextChunk chunk : chunks) {
            String content = chunk.getContent().toLowerCase();
            int matches = 0;
            for (String token : queryTokens) {
                if (content.contains(token)) {
                    matches++;
                }
            }
            
            if (matches > 0) {
                float score = (float) matches / queryTokens.length;
                results.add(new RetrievalResult(chunk, score, RetrievalStrategy.KEYWORD));
            }
        }
        
        Collections.sort(results, (a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }
    
    /**
     * Hybrid retrieval (combines semantic and keyword)
     */
    private List<RetrievalResult> retrieveHybrid(String query, int topK) {
        List<RetrievalResult> semanticResults = retrieveSemantic(query, topK * 2);
        List<RetrievalResult> keywordResults = retrieveKeyword(query, topK * 2);
        
        Map<String, Float> combinedScores = new HashMap<>();
        
        for (RetrievalResult result : semanticResults) {
            combinedScores.put(result.getChunk().getChunkId(), result.getScore() * 0.7f);
        }
        
        for (RetrievalResult result : keywordResults) {
            String id = result.getChunk().getChunkId();
            float current = combinedScores.getOrDefault(id, 0.0f);
            combinedScores.put(id, current + result.getScore() * 0.3f);
        }
        
        List<RetrievalResult> results = new ArrayList<>();
        for (Map.Entry<String, Float> entry : combinedScores.entrySet()) {
            TextChunk chunk = chunkIndex.get(entry.getKey());
            if (chunk != null) {
                results.add(new RetrievalResult(chunk, entry.getValue(), RetrievalStrategy.HYBRID));
            }
        }
        
        Collections.sort(results, (a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }
    
    /**
     * Recency-based retrieval
     */
    private List<RetrievalResult> retrieveRecency(String query, int topK) {
        List<RetrievalResult> results = new ArrayList<>();
        
        for (TextChunk chunk : chunks) {
            try {
                long timestamp = Long.parseLong(chunk.getTimestamp());
                long age = System.currentTimeMillis() - timestamp;
                float recencyScore = 1.0f / (1.0f + age / 86400000.0f);  // Decay over days
                results.add(new RetrievalResult(chunk, recencyScore, RetrievalStrategy.RECENCY));
            } catch (NumberFormatException e) {
                // Skip chunks with invalid timestamps
            }
        }
        
        Collections.sort(results, (a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }
    
    /**
     * Provide feedback to improve future retrievals (MemRL learning)
     * @param chunkIds List of chunk IDs that were retrieved
     * @param success Whether the retrieval was helpful
     */
    public void provideFeedback(List<String> chunkIds, boolean success) throws Exception {
        for (String chunkId : chunkIds) {
            TextChunk chunk = chunkIndex.get(chunkId);
            if (chunk != null) {
                chunk.updateQValue(success, DEFAULT_LEARNING_RATE);
                Log.d(TAG, "Updated Q-value for chunk " + chunkId + ": " + chunk.getQValue());
            }
        }
        saveChunks();
    }
    
    /**
     * Get MemRL statistics
     */
    public Map<String, Object> getMemRLStats() {
        Map<String, Object> stats = new HashMap<>();
        
        if (chunks.isEmpty()) {
            stats.put("avg_q_value", 0.0f);
            stats.put("success_rate", 0.0f);
            stats.put("total_retrievals", 0);
            return stats;
        }
        
        float sumQValue = 0.0f;
        int totalRetrievals = 0;
        int totalSuccesses = 0;
        
        for (TextChunk chunk : chunks) {
            sumQValue += chunk.getQValue();
            totalRetrievals += chunk.getRetrievalCount();
            totalSuccesses += chunk.getSuccessCount();
        }
        
        stats.put("avg_q_value", sumQValue / chunks.size());
        stats.put("success_rate", totalRetrievals > 0 ? (float) totalSuccesses / totalRetrievals : 0.0f);
        stats.put("total_retrievals", totalRetrievals);
        stats.put("total_chunks", chunks.size());
        
        return stats;
    }
    
    /**
     * Clear all chunks
     */
    public void clear() throws Exception {
        chunks.clear();
        chunkIndex.clear();
        storage.delete("rag_chunks_" + aiId);
        Log.d(TAG, "Cleared RAG store for AI: " + aiId);
    }
    
    // Helper methods
    
    private String generateChunkId(String content, String source) {
        try {
            String input = content.substring(0, Math.min(100, content.length())) + source + System.currentTimeMillis();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
    
    private int estimateTokens(String text) {
        // Rough estimate: ~4 characters per token
        return text.length() / 4;
    }
    
    private float[] generateEmbedding(String text) {
        // Simplified TF-IDF-like embedding (100 dimensions)
        // In production, use proper embedding model
        String[] words = text.toLowerCase().split("\\s+");
        float[] embedding = new float[100];
        
        for (String word : words) {
            int hash = Math.abs(word.hashCode() % 100);
            embedding[hash] += 1.0f;
        }
        
        // Normalize
        float norm = 0.0f;
        for (float val : embedding) {
            norm += val * val;
        }
        norm = (float) Math.sqrt(norm);
        
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }
        
        return embedding;
    }
    
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0f;
        
        float dot = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        
        return dot;  // Already normalized
    }
    
    private void saveChunks() throws Exception {
        JSONArray chunksArray = new JSONArray();
        
        for (TextChunk chunk : chunks) {
            JSONObject chunkObj = new JSONObject();
            chunkObj.put("chunkId", chunk.getChunkId());
            chunkObj.put("content", chunk.getContent());
            chunkObj.put("source", chunk.getSource());
            chunkObj.put("sourceType", chunk.getSourceType());
            chunkObj.put("timestamp", chunk.getTimestamp());
            chunkObj.put("tokenCount", chunk.getTokenCount());
            chunkObj.put("qValue", chunk.getQValue());
            chunkObj.put("retrievalCount", chunk.getRetrievalCount());
            chunkObj.put("successCount", chunk.getSuccessCount());
            
            // Save embedding
            if (chunk.getEmbedding() != null) {
                JSONArray embArray = new JSONArray();
                for (float val : chunk.getEmbedding()) {
                    embArray.put(val);
                }
                chunkObj.put("embedding", embArray);
            }
            
            chunksArray.put(chunkObj);
        }
        
        storage.store("rag_chunks_" + aiId, chunksArray.toString());
    }
    
    private void loadChunks() {
        try {
            String data = storage.retrieve("rag_chunks_" + aiId);
            if (data == null) {
                return;
            }
            
            JSONArray chunksArray = new JSONArray(data);
            for (int i = 0; i < chunksArray.length(); i++) {
                JSONObject chunkObj = chunksArray.getJSONObject(i);
                
                TextChunk chunk = new TextChunk(
                    chunkObj.getString("chunkId"),
                    chunkObj.getString("content"),
                    chunkObj.getString("source"),
                    chunkObj.getString("sourceType"),
                    chunkObj.getString("timestamp"),
                    chunkObj.getInt("tokenCount")
                );
                
                // Load MemRL values
                if (chunkObj.has("qValue")) {
                    chunk.updateQValue(false, 0);  // Initialize without changing
                    // Set values directly via reflection or other means
                }
                
                // Load embedding
                if (chunkObj.has("embedding")) {
                    JSONArray embArray = chunkObj.getJSONArray("embedding");
                    float[] embedding = new float[embArray.length()];
                    for (int j = 0; j < embArray.length(); j++) {
                        embedding[j] = (float) embArray.getDouble(j);
                    }
                    chunk.setEmbedding(embedding);
                }
                
                chunks.add(chunk);
                chunkIndex.put(chunk.getChunkId(), chunk);
            }
            
            Log.d(TAG, "Loaded " + chunks.size() + " chunks for AI: " + aiId);
        } catch (Exception e) {
            Log.e(TAG, "Error loading chunks", e);
        }
    }
}
