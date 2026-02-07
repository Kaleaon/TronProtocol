package com.tronprotocol.app.selfmod;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of self-reflection on behavior
 */
public class ReflectionResult {
    private final List<String> insights;
    private final List<String> suggestions;
    
    public ReflectionResult() {
        this.insights = new ArrayList<>();
        this.suggestions = new ArrayList<>();
    }
    
    public void addInsight(String insight) {
        insights.add(insight);
    }
    
    public void addSuggestion(String suggestion) {
        suggestions.add(suggestion);
    }
    
    public List<String> getInsights() {
        return new ArrayList<>(insights);
    }
    
    public List<String> getSuggestions() {
        return new ArrayList<>(suggestions);
    }
    
    public boolean hasInsights() {
        return !insights.isEmpty();
    }
    
    public boolean hasSuggestions() {
        return !suggestions.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ReflectionResult{" +
                "insights=" + insights.size() +
                ", suggestions=" + suggestions.size() +
                '}';
    }
}
