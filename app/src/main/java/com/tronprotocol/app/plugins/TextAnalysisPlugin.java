package com.tronprotocol.app.plugins;

import android.content.Context;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text Analysis Plugin
 * 
 * Provides text processing utilities
 * Inspired by ToolNeuron's DevUtilsPlugin and landseek's tools
 */
public class TextAnalysisPlugin implements Plugin {
    private static final String TAG = "TextAnalysisPlugin";
    private static final String ID = "text_analysis";
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Text Analysis";
    }
    
    @Override
    public String getDescription() {
        return "Analyze text: word count, character count, extract URLs/emails, " +
               "find patterns, transform text (uppercase, lowercase, reverse).";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    @Override
    public PluginResult execute(String input) throws Exception {
        long startTime = System.currentTimeMillis();
        
        try {
            // Parse command: "command|text"
            String[] parts = input.split("\\|", 2);
            if (parts.length < 2) {
                throw new Exception("Invalid format. Use: command|text");
            }
            
            String command = parts[0].trim().toLowerCase();
            String text = parts[1];
            
            String result;
            switch (command) {
                case "count":
                case "stats":
                    result = getTextStats(text);
                    break;
                case "uppercase":
                    result = text.toUpperCase();
                    break;
                case "lowercase":
                    result = text.toLowerCase();
                    break;
                case "reverse":
                    result = new StringBuilder(text).reverse().toString();
                    break;
                case "extract_urls":
                    result = extractUrls(text);
                    break;
                case "extract_emails":
                    result = extractEmails(text);
                    break;
                case "word_count":
                    result = String.valueOf(countWords(text));
                    break;
                case "char_count":
                    result = String.valueOf(text.length());
                    break;
                default:
                    throw new Exception("Unknown command: " + command);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.success(result, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.error("Text analysis failed: " + e.getMessage(), duration);
        }
    }
    
    /**
     * Get comprehensive text statistics
     */
    private String getTextStats(String text) {
        int charCount = text.length();
        int wordCount = countWords(text);
        int lineCount = text.split("\n").length;
        int sentenceCount = text.split("[.!?]+").length;
        
        return "Characters: " + charCount + "\n" +
               "Words: " + wordCount + "\n" +
               "Lines: " + lineCount + "\n" +
               "Sentences: " + sentenceCount;
    }
    
    /**
     * Count words in text
     */
    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
    
    /**
     * Extract all URLs from text
     */
    private String extractUrls(String text) {
        Pattern urlPattern = Pattern.compile(
            "(https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/[^\\s]*)?)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = urlPattern.matcher(text);
        StringBuilder urls = new StringBuilder();
        int count = 0;
        
        while (matcher.find()) {
            urls.append(matcher.group()).append("\n");
            count++;
        }
        
        if (count == 0) {
            return "No URLs found";
        }
        
        return "Found " + count + " URL(s):\n" + urls.toString();
    }
    
    /**
     * Extract all email addresses from text
     */
    private String extractEmails(String text) {
        Pattern emailPattern = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = emailPattern.matcher(text);
        StringBuilder emails = new StringBuilder();
        int count = 0;
        
        while (matcher.find()) {
            emails.append(matcher.group()).append("\n");
            count++;
        }
        
        if (count == 0) {
            return "No email addresses found";
        }
        
        return "Found " + count + " email(s):\n" + emails.toString();
    }
    
    @Override
    public void initialize(Context context) {
        this.context = context;
    }
    
    @Override
    public void destroy() {
        this.context = null;
    }
}
