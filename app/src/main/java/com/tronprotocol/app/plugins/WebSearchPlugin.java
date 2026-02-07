package com.tronprotocol.app.plugins;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web Search Plugin
 * 
 * Provides web search capabilities using DuckDuckGo (privacy-focused)
 * Inspired by ToolNeuron's WebSearchPlugin and landseek's tools
 */
public class WebSearchPlugin implements Plugin {
    private static final String TAG = "WebSearchPlugin";
    private static final String ID = "web_search";
    private static final int DEFAULT_RESULTS = 5;
    private static final int TIMEOUT_MS = 10000;
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "Web Search";
    }
    
    @Override
    public String getDescription() {
        return "Search the web using DuckDuckGo. Returns relevant search results with titles, snippets, and URLs.";
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
        
        // Parse input - format: "query|max_results"
        String[] parts = input.split("\\|");
        String query = parts[0].trim();
        int maxResults = parts.length > 1 ? Integer.parseInt(parts[1]) : DEFAULT_RESULTS;
        
        Log.d(TAG, "Searching for: " + query + " (max " + maxResults + " results)");
        
        try {
            String results = searchDuckDuckGo(query, maxResults);
            long duration = System.currentTimeMillis() - startTime;
            
            return PluginResult.success(results, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.error("Web search failed: " + e.getMessage(), duration);
        }
    }
    
    /**
     * Search using DuckDuckGo HTML API
     */
    private String searchDuckDuckGo(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        String searchUrl = "https://html.duckduckgo.com/html/?q=" + encodedQuery;
        
        StringBuilder result = new StringBuilder();
        result.append("Web Search Results for: ").append(query).append("\n\n");
        
        HttpURLConnection connection = null;
        try {
            URL url = new URL(searchUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "TronProtocol/1.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream())
            );
            
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
            reader.close();
            
            // Parse HTML for results
            int count = parseSearchResults(html.toString(), result, maxResults);
            
            if (count == 0) {
                result.append("No results found.");
            }
            
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        
        return result.toString();
    }
    
    /**
     * Parse DuckDuckGo HTML results (simplified parser)
     */
    private int parseSearchResults(String html, StringBuilder output, int maxResults) {
        int count = 0;
        
        // Very simplified HTML parsing - in production use proper HTML parser
        Pattern titlePattern = Pattern.compile("<a[^>]+class=\"result__a\"[^>]*>([^<]+)</a>");
        Pattern snippetPattern = Pattern.compile("<a[^>]+class=\"result__snippet\"[^>]*>([^<]+)</a>");
        Pattern urlPattern = Pattern.compile("<a[^>]+class=\"result__url\"[^>]*>([^<]+)</a>");
        
        Matcher titleMatcher = titlePattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);
        Matcher urlMatcher = urlPattern.matcher(html);
        
        while (count < maxResults && titleMatcher.find()) {
            String title = cleanHtml(titleMatcher.group(1));
            String snippet = snippetMatcher.find() ? cleanHtml(snippetMatcher.group(1)) : "No description";
            String url = urlMatcher.find() ? cleanHtml(urlMatcher.group(1)) : "No URL";
            
            output.append(count + 1).append(". ").append(title).append("\n");
            output.append("   ").append(snippet).append("\n");
            output.append("   URL: ").append(url).append("\n\n");
            
            count++;
        }
        
        return count;
    }
    
    /**
     * Clean HTML entities and tags
     */
    private String cleanHtml(String text) {
        return text
            .replaceAll("<[^>]+>", "")
            .replaceAll("&quot;", "\"")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .trim();
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
