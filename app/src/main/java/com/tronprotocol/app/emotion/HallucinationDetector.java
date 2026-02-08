package com.tronprotocol.app.emotion;

import android.content.Context;
import android.util.Log;

import com.tronprotocol.app.rag.RAGStore;
import com.tronprotocol.app.rag.RetrievalResult;
import com.tronprotocol.app.rag.RetrievalStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Advanced Hallucination Detector
 * 
 * Integrates state-of-the-art techniques from:
 * - SelfCheckGPT (arXiv:2303.08896) - Consistency-based detection
 * - awesome-hallucination-detection (EdinburghNLP) - Multiple detection methods
 * 
 * Detection strategies:
 * 1. Self-Consistency: Multiple generations compared for agreement
 * 2. Retrieval-Augmented: Compare response against retrieved facts
 * 3. Uncertainty Estimation: Internal confidence scoring
 * 4. Claim Decomposition: Break response into verifiable claims
 * 5. Temporal Decay: Recent embarrassments increase caution
 */
public class HallucinationDetector {
    private static final String TAG = "HallucinationDetector";
    private static final float CONSISTENCY_THRESHOLD = 0.7f;
    private static final float CONFIDENCE_THRESHOLD = 0.6f;
    
    private final Context context;
    private final EmotionalStateManager emotionalManager;
    private RAGStore ragStore;  // Optional - for retrieval-augmented verification
    
    public HallucinationDetector(Context context, EmotionalStateManager emotionalManager) {
        this.context = context;
        this.emotionalManager = emotionalManager;
    }
    
    public void setRAGStore(RAGStore ragStore) {
        this.ragStore = ragStore;
    }
    
    /**
     * Comprehensive hallucination detection pipeline
     * 
     * @param response The AI-generated response to check
     * @param alternativeResponses Multiple alternative generations for consistency check
     * @param prompt Original prompt for context
     * @return HallucinationResult with detection details
     */
    public HallucinationResult detectHallucination(String response, 
                                                   List<String> alternativeResponses,
                                                   String prompt) {
        HallucinationResult result = new HallucinationResult();
        result.response = response;
        
        // Strategy 1: Self-Consistency Check (SelfCheckGPT)
        if (alternativeResponses != null && !alternativeResponses.isEmpty()) {
            List<String> allResponses = new ArrayList<>();
            allResponses.add(response);
            allResponses.addAll(alternativeResponses);
            
            EmotionalStateManager.ConsistencyResult consistency = 
                emotionalManager.checkConsistency(allResponses);
            
            result.consistencyScore = consistency.similarityScore;
            result.isConsistent = consistency.isConsistent;
            
            if (!consistency.isConsistent) {
                result.hallucinationType = HallucinationType.INCONSISTENT;
                result.confidence = 0.9f;  // High confidence it's a hallucination
                Log.w(TAG, "Inconsistency detected: " + consistency.assessment);
            }
        }
        
        // Strategy 2: Retrieval-Augmented Verification
        if (ragStore != null) {
            try {
                List<RetrievalResult> retrievedFacts = ragStore.retrieve(
                    prompt, RetrievalStrategy.MEMRL, 5);
                
                if (!retrievedFacts.isEmpty()) {
                    float factualSupport = calculateFactualSupport(response, retrievedFacts);
                    result.factualSupportScore = factualSupport;
                    
                    if (factualSupport < 0.3f) {
                        result.hallucinationType = HallucinationType.UNSUPPORTED;
                        result.confidence = Math.max(result.confidence, 0.7f);
                        Log.w(TAG, "Low factual support: " + factualSupport);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in RAG verification", e);
            }
        }
        
        // Strategy 3: Claim Decomposition & Verification
        List<String> claims = decomposeClaims(response);
        result.claims = claims;
        result.claimCount = claims.size();
        
        if (claims.size() > 5 && result.consistencyScore < 0.5f) {
            // Many specific claims with low consistency = likely hallucinating details
            result.hallucinationType = HallucinationType.OVERSPECIFIC;
            result.confidence = Math.max(result.confidence, 0.75f);
        }
        
        // Strategy 4: Uncertainty Patterns
        float uncertaintyScore = detectUncertaintyPatterns(response);
        result.uncertaintyScore = uncertaintyScore;
        
        if (uncertaintyScore > 0.7f) {
            // AI is uncertain but still generating - risky
            result.hallucinationType = HallucinationType.UNCERTAIN_GENERATION;
            result.confidence = Math.max(result.confidence, 0.6f);
        }
        
        // Strategy 5: Emotional Bias (recent embarrassments)
        float emotionalBias = emotionalManager.getEmotionalBias();
        result.emotionalBias = emotionalBias;
        
        // Final determination
        result.isHallucination = determineHallucination(result);
        
        // Apply emotional learning if hallucination detected
        if (result.isHallucination) {
            String context = String.format("Hallucination detected (%s): %s", 
                                         result.hallucinationType, 
                                         response.substring(0, Math.min(50, response.length())));
            emotionalManager.applyEmbarrassment(context, result.confidence);
        }
        
        return result;
    }
    
    /**
     * Calculate how well the response is supported by retrieved facts
     */
    private float calculateFactualSupport(String response, List<RetrievalResult> facts) {
        if (facts.isEmpty()) return 0.0f;
        
        String responseLower = response.toLowerCase();
        int totalSupport = 0;
        
        for (RetrievalResult fact : facts) {
            String factContent = fact.getChunk().getContent().toLowerCase();
            
            // Calculate word overlap
            String[] responseWords = responseLower.split("\\s+");
            String[] factWords = factContent.split("\\s+");
            
            int overlap = 0;
            for (String rWord : responseWords) {
                for (String fWord : factWords) {
                    if (rWord.equals(fWord) && rWord.length() > 3) {
                        overlap++;
                    }
                }
            }
            
            totalSupport += overlap;
        }
        
        // Normalize by response length
        String[] responseWords = responseLower.split("\\s+");
        return Math.min(1.0f, totalSupport / (float) Math.max(1, responseWords.length));
    }
    
    /**
     * Decompose response into verifiable atomic claims
     * Based on HALoGEN and claim decomposition research
     */
    private List<String> decomposeClaims(String response) {
        List<String> claims = new ArrayList<>();
        
        // Simple sentence-based decomposition
        String[] sentences = response.split("[.!?]+");
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() > 10) {
                claims.add(sentence);
            }
        }
        
        return claims;
    }
    
    /**
     * Detect uncertainty patterns in the response
     * Phrases like "I think", "maybe", "possibly" indicate uncertainty
     */
    private float detectUncertaintyPatterns(String response) {
        String[] uncertainPhrases = {
            "i think", "maybe", "possibly", "might be", "could be",
            "probably", "perhaps", "i'm not sure", "uncertain",
            "not certain", "guess", "assume"
        };
        
        String responseLower = response.toLowerCase();
        int uncertaintyCount = 0;
        
        for (String phrase : uncertainPhrases) {
            if (responseLower.contains(phrase)) {
                uncertaintyCount++;
            }
        }
        
        // Normalize by number of sentences
        int sentenceCount = response.split("[.!?]+").length;
        return Math.min(1.0f, uncertaintyCount / (float) Math.max(1, sentenceCount));
    }
    
    /**
     * Make final hallucination determination based on multiple signals
     */
    private boolean determineHallucination(HallucinationResult result) {
        // High confidence hallucination signals
        if (result.confidence > 0.85f) return true;
        
        // Multiple weak signals
        int weakSignals = 0;
        if (!result.isConsistent) weakSignals++;
        if (result.factualSupportScore < 0.4f) weakSignals++;
        if (result.uncertaintyScore > 0.5f) weakSignals++;
        if (result.emotionalBias < -0.1f) weakSignals++;  // Recently embarrassed
        
        if (weakSignals >= 3) return true;
        
        // Conservative: only flag clear hallucinations
        return false;
    }
    
    /**
     * Get recommendation for handling potential hallucination
     */
    public String getRecommendation(HallucinationResult result) {
        if (!result.isHallucination) {
            return "Response appears factual. Proceed with confidence.";
        }
        
        switch (result.hallucinationType) {
            case INCONSISTENT:
                return "Multiple generations inconsistent. Regenerate or defer to uncertainty.";
            case UNSUPPORTED:
                return "No factual support found. Retrieve more context or admit uncertainty.";
            case OVERSPECIFIC:
                return "Too many specific claims without support. Simplify or verify claims.";
            case UNCERTAIN_GENERATION:
                return "AI is uncertain. Better to say 'I don't know' than guess.";
            default:
                return "Potential hallucination detected. Exercise caution.";
        }
    }
    
    /**
     * Types of hallucinations detected
     */
    public enum HallucinationType {
        NONE,
        INCONSISTENT,        // SelfCheckGPT: responses don't agree
        UNSUPPORTED,         // RAG: no factual support found
        OVERSPECIFIC,        // Too many specific claims
        UNCERTAIN_GENERATION,  // Generating despite uncertainty
        UNKNOWN
    }
    
    /**
     * Hallucination detection result
     */
    public static class HallucinationResult {
        public String response;
        public boolean isHallucination = false;
        public HallucinationType hallucinationType = HallucinationType.NONE;
        public float confidence = 0.0f;  // Confidence that it's a hallucination
        
        // Detection signals
        public boolean isConsistent = true;
        public float consistencyScore = 1.0f;
        public float factualSupportScore = 0.0f;
        public float uncertaintyScore = 0.0f;
        public float emotionalBias = 0.0f;
        
        // Claims analysis
        public List<String> claims = new ArrayList<>();
        public int claimCount = 0;
        
        @Override
        public String toString() {
            return String.format(
                "Hallucination: %s (%.2f confidence)\n" +
                "Type: %s\n" +
                "Consistency: %.2f\n" +
                "Factual Support: %.2f\n" +
                "Uncertainty: %.2f\n" +
                "Claims: %d",
                isHallucination ? "YES" : "NO",
                confidence,
                hallucinationType,
                consistencyScore,
                factualSupportScore,
                uncertaintyScore,
                claimCount
            );
        }
    }
}
