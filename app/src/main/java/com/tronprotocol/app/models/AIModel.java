package com.tronprotocol.app.models;

/**
 * Represents an AI model that can be loaded and used for inference
 * 
 * Inspired by ToolNeuron's model management system
 */
public class AIModel {
    private String id;
    private String name;
    private String path;
    private String modelType;  // e.g., "GGUF", "TFLite"
    private long size;         // Size in bytes
    private boolean isLoaded;
    private String category;   // e.g., "General", "Medical", "Coding"
    
    public AIModel(String id, String name, String path, String modelType, long size) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.modelType = modelType;
        this.size = size;
        this.isLoaded = false;
        this.category = "General";
    }
    
    // Getters and Setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public String getModelType() {
        return modelType;
    }
    
    public void setModelType(String modelType) {
        this.modelType = modelType;
    }
    
    public long getSize() {
        return size;
    }
    
    public void setSize(long size) {
        this.size = size;
    }
    
    public boolean isLoaded() {
        return isLoaded;
    }
    
    public void setLoaded(boolean loaded) {
        isLoaded = loaded;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    @Override
    public String toString() {
        return "AIModel{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", modelType='" + modelType + '\'' +
                ", size=" + formatSize(size) +
                ", isLoaded=" + isLoaded +
                ", category='" + category + '\'' +
                '}';
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
}
