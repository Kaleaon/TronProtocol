package com.tronprotocol.app.aimodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a trained AI model created from knowledge
 */
public class TrainedModel {
    private String id;
    private String name;
    private String category;
    private int conceptCount;
    private int knowledgeSize;
    private double accuracy;
    private int trainingIterations;
    private long createdTimestamp;
    private long lastTrainedTimestamp;
    
    // Model data
    private List<String> knowledgeBase;
    private List<float[]> embeddings;
    private Map<String, Object> parameters;
    
    public TrainedModel(String id, String name, String category, int conceptCount,
                       int knowledgeSize, long createdTimestamp) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.conceptCount = conceptCount;
        this.knowledgeSize = knowledgeSize;
        this.createdTimestamp = createdTimestamp;
        this.lastTrainedTimestamp = createdTimestamp;
        this.accuracy = 0.0;
        this.trainingIterations = 0;
        
        this.knowledgeBase = new ArrayList<>();
        this.embeddings = new ArrayList<>();
        this.parameters = new HashMap<>();
    }
    
    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public int getConceptCount() { return conceptCount; }
    public int getKnowledgeSize() { return knowledgeSize; }
    public double getAccuracy() { return accuracy; }
    public int getTrainingIterations() { return trainingIterations; }
    public long getCreatedTimestamp() { return createdTimestamp; }
    public long getLastTrainedTimestamp() { return lastTrainedTimestamp; }
    public List<String> getKnowledgeBase() { return knowledgeBase; }
    public List<float[]> getEmbeddings() { return embeddings; }
    public Map<String, Object> getParameters() { return parameters; }
    
    // Setters
    public void setAccuracy(double accuracy) { this.accuracy = accuracy; }
    public void setTrainingIterations(int iterations) { this.trainingIterations = iterations; }
    public void setLastTrainedTimestamp(long timestamp) { this.lastTrainedTimestamp = timestamp; }
    public void setKnowledgeBase(List<String> knowledgeBase) { this.knowledgeBase = knowledgeBase; }
    public void setEmbeddings(List<float[]> embeddings) { this.embeddings = embeddings; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    
    @Override
    public String toString() {
        return "TrainedModel{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", category='" + category + '\'' +
                ", accuracy=" + String.format("%.2f%%", accuracy * 100) +
                ", concepts=" + conceptCount +
                ", iterations=" + trainingIterations +
                '}';
    }
}
