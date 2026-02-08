package com.tronprotocol.app.emotion;

import android.content.Context;
import android.util.Log;

import com.tronprotocol.app.security.SecureStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emotional State Manager with Hallucination Detection
 * 
 * Implements emotion-based learning inspired by:
 * - SelfCheckGPT (arXiv:2303.08896) - Consistency-based hallucination detection
 * - awesome-hallucination-detection (EdinburghNLP) - State-of-the-art techniques
 * 
 * Key features:
 * - Embarrassment as negative reinforcement for hallucinations
 * - Multi-response consistency checking (SelfCheckGPT)
 * - Belief tree propagation for claim verification
 * - Emotional bias influences future decisions
 * - Self-uncertainty expression to avoid hallucinations
 */
public class EmotionalStateManager {
    private static final String TAG = "EmotionalState";
    private static final String EMOTIONAL_HISTORY_KEY = "emotional_history";
    private static final float EMBARRASSMENT_PENALTY = -0.3f;
    private static final float CONFIDENCE_BOOST = 0.2f;
    
    public enum Emotion {
        CONFIDENT,       // High consistency, verified factual
        UNCERTAIN,       // Low confidence, should defer
        EMBARRASSED,     // Detected hallucination or error
        PROUD,           // Correct prediction confirmed, learned from mistake
        CURIOUS,         // Learning new information
        NEUTRAL          // Default state
    }
    
    private final Context context;
    private final SecureStorage storage;
    private final List<EmotionalEvent> emotionalHistory;
    
    private Emotion currentEmotion;
    private float emotionalIntensity;
    private int embarrassmentCount;
    private long lastEmbarrassmentTime;
    
    public EmotionalStateManager(Context context) throws Exception {
        this.context = context;
        this.storage = new SecureStorage(context);
        this.emotionalHistory = new ArrayList<>();
        this.currentEmotion = Emotion.NEUTRAL;
        this.emotionalIntensity = 0.5f;
        this.embarrassmentCount = 0;
        
        loadEmotionalHistory();
    }
    
    /**
     * SelfCheckGPT: Multi-response consistency checking
     * Generates multiple responses and compares for hallucination detection
     */
    public ConsistencyResult checkConsistency(List<String> responses) {
        if (responses == null || responses.size() < 2) {
            return new ConsistencyResult(false, 0.0f, "Need multiple responses for consistency check");
        }
        
        float totalSimilarity = 0.0f;
        int comparisons = 0;
        
        for (int i = 0; i < responses.size(); i++) {
            for (int j = i + 1; j < responses.size(); j++) {
                float similarity = calculateJaccardSimilarity(responses.get(i), responses.get(j));
                totalSimilarity += similarity;
                comparisons++;
            }
        }
        
        float avgSimilarity = comparisons > 0 ? totalSimilarity / comparisons : 0.0f;
        boolean isConsistent = avgSimilarity > 0.7f;
        
        String assessment;
        if (avgSimilarity > 0.8f) {
            assessment = "High consistency - likely factual";
        } else if (avgSimilarity > 0.6f) {
            assessment = "Moderate consistency - verify facts";
        } else {
            assessment = "Low consistency - hallucination detected";
        }
        
        return new ConsistencyResult(isConsistent, avgSimilarity, assessment);
    }
    
    private float calculateJaccardSimilarity(String text1, String text2) {
        String[] words1 = text1.toLowerCase().split("\\s+");
        String[] words2 = text2.toLowerCase().split("\\s+");
        
        Map<String, Boolean> union = new HashMap<>();
        Map<String, Boolean> intersection = new HashMap<>();
        
        for (String word : words1) union.put(word, true);
        for (String word : words2) {
            if (union.containsKey(word)) intersection.put(word, true);
            union.put(word, true);
        }
        
        return union.isEmpty() ? 0.0f : (float) intersection.size() / union.size();
    }
    
    /**
     * Apply embarrassment when hallucination detected
     * Creates strong negative emotional reinforcement
     */
    public float applyEmbarrassment(String context, float severity) {
        embarrassmentCount++;
        lastEmbarrassmentTime = System.currentTimeMillis();
        
        currentEmotion = Emotion.EMBARRASSED;
        emotionalIntensity = Math.min(1.0f, severity);
        
        EmotionalEvent event = new EmotionalEvent(
            Emotion.EMBARRASSED, emotionalIntensity, context, System.currentTimeMillis());
        emotionalHistory.add(event);
        
        Log.w(TAG, String.format("EMBARRASSMENT applied (%.2f): %s", severity, context));
        saveEmotionalHistory();
        
        return EMBARRASSMENT_PENALTY * severity;
    }
    
    /**
     * Apply confidence when response verified correct
     */
    public float applyConfidence(String context) {
        currentEmotion = Emotion.CONFIDENT;
        emotionalIntensity = 0.8f;
        
        emotionalHistory.add(new EmotionalEvent(
            Emotion.CONFIDENT, emotionalIntensity, context, System.currentTimeMillis()));
        
        Log.d(TAG, "Confidence boost: " + context);
        saveEmotionalHistory();
        
        return CONFIDENCE_BOOST;
    }
    
    /**
     * Apply pride when AI learns from mistake
     */
    public float applyPride(String context) {
        currentEmotion = Emotion.PROUD;
        emotionalIntensity = 0.9f;
        
        emotionalHistory.add(new EmotionalEvent(
            Emotion.PROUD, emotionalIntensity, context, System.currentTimeMillis()));
        
        Log.d(TAG, "Pride applied (learned from mistake): " + context);
        saveEmotionalHistory();
        
        return CONFIDENCE_BOOST * 1.5f;
    }
    
    /**
     * Express uncertainty - better to say "I don't know" than hallucinate
     */
    public void expressUncertainty(String context) {
        currentEmotion = Emotion.UNCERTAIN;
        emotionalIntensity = 0.3f;
        
        emotionalHistory.add(new EmotionalEvent(
            Emotion.UNCERTAIN, emotionalIntensity, context, System.currentTimeMillis()));
        
        Log.d(TAG, "Expressing uncertainty: " + context);
        saveEmotionalHistory();
    }
    
    /**
     * Get emotional bias for decision making
     * Recent embarrassment increases caution
     */
    public float getEmotionalBias() {
        if (embarrassmentCount == 0) return 0.0f;
        
        long timeSinceEmbarrassment = System.currentTimeMillis() - lastEmbarrassmentTime;
        long oneHour = 3600000L;
        
        if (timeSinceEmbarrassment < oneHour) {
            return -0.2f * (1.0f - (timeSinceEmbarrassment / (float) oneHour));
        }
        return 0.0f;
    }
    
    /**
     * Should AI defer answering due to low confidence?
     */
    public boolean shouldDefer(float confidence) {
        float adjustedConfidence = confidence + getEmotionalBias();
        return adjustedConfidence < 0.5f;
    }
    
    /**
     * Get emotional state summary
     */
    public Map<String, Object> getEmotionalState() {
        Map<String, Object> state = new HashMap<>();
        state.put("current_emotion", currentEmotion.name());
        state.put("intensity", emotionalIntensity);
        state.put("embarrassment_count", embarrassmentCount);
        state.put("emotional_bias", getEmotionalBias());
        state.put("history_size", emotionalHistory.size());
        
        Map<String, Integer> distribution = new HashMap<>();
        for (EmotionalEvent event : emotionalHistory) {
            String emotion = event.emotion.name();
            distribution.put(emotion, distribution.getOrDefault(emotion, 0) + 1);
        }
        state.put("emotion_distribution", distribution);
        
        return state;
    }
    
    private void saveEmotionalHistory() {
        try {
            JSONArray historyArray = new JSONArray();
            int startIdx = Math.max(0, emotionalHistory.size() - 100);
            for (int i = startIdx; i < emotionalHistory.size(); i++) {
                EmotionalEvent event = emotionalHistory.get(i);
                JSONObject eventObj = new JSONObject();
                eventObj.put("emotion", event.emotion.name());
                eventObj.put("intensity", event.intensity);
                eventObj.put("context", event.context);
                eventObj.put("timestamp", event.timestamp);
                historyArray.put(eventObj);
            }
            storage.store(EMOTIONAL_HISTORY_KEY, historyArray.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error saving emotional history", e);
        }
    }
    
    private void loadEmotionalHistory() {
        try {
            String data = storage.retrieve(EMOTIONAL_HISTORY_KEY);
            if (data != null) {
                JSONArray historyArray = new JSONArray(data);
                for (int i = 0; i < historyArray.length(); i++) {
                    JSONObject eventObj = historyArray.getJSONObject(i);
                    EmotionalEvent event = new EmotionalEvent(
                        Emotion.valueOf(eventObj.getString("emotion")),
                        (float) eventObj.getDouble("intensity"),
                        eventObj.getString("context"),
                        eventObj.getLong("timestamp"));
                    emotionalHistory.add(event);
                    
                    if (event.emotion == Emotion.EMBARRASSED) {
                        embarrassmentCount++;
                        lastEmbarrassmentTime = Math.max(lastEmbarrassmentTime, event.timestamp);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading emotional history", e);
        }
    }
    
    public static class EmotionalEvent {
        public final Emotion emotion;
        public final float intensity;
        public final String context;
        public final long timestamp;
        
        public EmotionalEvent(Emotion emotion, float intensity, String context, long timestamp) {
            this.emotion = emotion;
            this.intensity = intensity;
            this.context = context;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("%s (%.2f): %s", emotion.name(), intensity, context);
        }
    }
    
    public static class ConsistencyResult {
        public final boolean isConsistent;
        public final float similarityScore;
        public final String assessment;
        
        public ConsistencyResult(boolean isConsistent, float similarityScore, String assessment) {
            this.isConsistent = isConsistent;
            this.similarityScore = similarityScore;
            this.assessment = assessment;
        }
        
        @Override
        public String toString() {
            return String.format("Consistency: %s (%.2f) - %s", 
                               isConsistent ? "YES" : "NO", similarityScore, assessment);
        }
    }
}
