package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Web Search Plugin.
 *
 * Provides web search capabilities using DuckDuckGo (privacy-focused).
 * Inspired by ToolNeuron's WebSearchPlugin and landseek's tools.
 */
class WebSearchPlugin : Plugin {

    companion object {
        private const val TAG = "WebSearchPlugin"
        private const val ID = "web_search"
        private const val DEFAULT_RESULTS = 5
        private const val TIMEOUT_MS = 10000
    }

    private var context: Context? = null

    override val id: String = ID

    override val name: String = "Web Search"

    override val description: String =
        "Search the web using DuckDuckGo. Returns relevant search results with titles, snippets, and URLs."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        // Parse input - format: "query|max_results"
        val parts = input.split("\\|".toRegex())
        val query = parts[0].trim()
        val maxResults = if (parts.size > 1) parts[1].toInt() else DEFAULT_RESULTS

        Log.d(TAG, "Searching for: $query (max $maxResults results)")

        return try {
            val results = searchDuckDuckGo(query, maxResults)
            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(results, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("Web search failed: ${e.message}", duration)
        }
    }

    /**
     * Search using DuckDuckGo HTML API.
     */
    private fun searchDuckDuckGo(query: String, maxResults: Int): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val result = StringBuilder()
        result.append("Web Search Results for: $query\n\n")

        var connection: HttpURLConnection? = null
        try {
            val url = URL(searchUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "TronProtocol/1.0")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw Exception("HTTP $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val html = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                html.append(line).append("\n")
            }
            reader.close()

            // Parse HTML for results
            val count = parseSearchResults(html.toString(), result, maxResults)

            if (count == 0) {
                result.append("No results found.")
            }
        } finally {
            connection?.disconnect()
        }

        return result.toString()
    }

    /**
     * Parse DuckDuckGo HTML results (simplified parser).
     */
    private fun parseSearchResults(html: String, output: StringBuilder, maxResults: Int): Int {
        var count = 0

        // Very simplified HTML parsing - in production use proper HTML parser
        val titlePattern = Pattern.compile("<a[^>]+class=\"result__a\"[^>]*>([^<]+)</a>")
        val snippetPattern = Pattern.compile("<a[^>]+class=\"result__snippet\"[^>]*>([^<]+)</a>")
        val urlPattern = Pattern.compile("<a[^>]+class=\"result__url\"[^>]*>([^<]+)</a>")

        val titleMatcher = titlePattern.matcher(html)
        val snippetMatcher = snippetPattern.matcher(html)
        val urlMatcher = urlPattern.matcher(html)

        while (count < maxResults && titleMatcher.find()) {
            val title = cleanHtml(titleMatcher.group(1))
            val snippet = if (snippetMatcher.find()) cleanHtml(snippetMatcher.group(1)) else "No description"
            val url = if (urlMatcher.find()) cleanHtml(urlMatcher.group(1)) else "No URL"

            output.append("${count + 1}. $title\n")
            output.append("   $snippet\n")
            output.append("   URL: $url\n\n")

            count++
        }

        return count
    }

    /**
     * Clean HTML entities and tags.
     */
    private fun cleanHtml(text: String): String =
        text.replace("<[^>]+>".toRegex(), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
    }
}
