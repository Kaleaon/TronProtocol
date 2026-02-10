package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Web Search Plugin.
 *
 * Provides web search capabilities using DuckDuckGo (privacy-focused).
 * Includes an in-memory result cache to avoid redundant requests.
 */
class WebSearchPlugin : Plugin {

    companion object {
        private const val TAG = "WebSearchPlugin"
        private const val ID = "web_search"
        private const val DEFAULT_RESULTS = 5
        private const val MAX_RESULTS = 20
        private const val TIMEOUT_MS = 10000
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 50
    }

    private var context: Context? = null
    private val cache = ConcurrentHashMap<String, CachedResult>()

    private data class CachedResult(val data: String, val timestamp: Long)

    override val id: String = ID

    override val name: String = "Web Search"

    override val description: String =
        "Search the web using DuckDuckGo. Returns relevant search results with titles, snippets, and URLs. " +
            "Format: query or query|max_results (default 5, max 20)."

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        val parts = input.split("\\|".toRegex(), 2)
        val query = parts[0].trim()

        if (query.isEmpty()) {
            return PluginResult.error("Search query cannot be empty.", System.currentTimeMillis() - startTime)
        }

        val maxResults = if (parts.size > 1) {
            val parsed = parts[1].trim().toIntOrNull()
            if (parsed == null || parsed < 1) DEFAULT_RESULTS
            else parsed.coerceAtMost(MAX_RESULTS)
        } else {
            DEFAULT_RESULTS
        }

        Log.d(TAG, "Searching for: $query (max $maxResults results)")

        return try {
            val cacheKey = "$query|$maxResults"
            val cached = cache[cacheKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached result for: $query")
                val duration = System.currentTimeMillis() - startTime
                return PluginResult.success(cached.data, duration)
            }

            val results = searchDuckDuckGo(query, maxResults)
            val duration = System.currentTimeMillis() - startTime

            // Evict old entries if cache is full
            if (cache.size >= MAX_CACHE_SIZE) {
                val oldest = cache.entries.minByOrNull { it.value.timestamp }
                if (oldest != null) cache.remove(oldest.key)
            }
            cache[cacheKey] = CachedResult(results, System.currentTimeMillis())

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
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw Exception("HTTP $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val html = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                html.append(line).append("\n")
            }
            reader.close()

            val count = parseSearchResults(html.toString(), result, maxResults)

            if (count == 0) {
                result.append("No results found.")
            } else {
                result.append("--- $count result(s) returned ---")
            }
        } finally {
            connection?.disconnect()
        }

        return result.toString()
    }

    /**
     * Parse DuckDuckGo HTML results.
     */
    private fun parseSearchResults(html: String, output: StringBuilder, maxResults: Int): Int {
        var count = 0

        val titlePattern = Pattern.compile("<a[^>]+class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL)
        val snippetPattern = Pattern.compile("<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL)
        val urlPattern = Pattern.compile("<a[^>]+class=\"result__url\"[^>]*>(.*?)</a>", Pattern.DOTALL)

        val titleMatcher = titlePattern.matcher(html)
        val snippetMatcher = snippetPattern.matcher(html)
        val urlMatcher = urlPattern.matcher(html)

        while (count < maxResults && titleMatcher.find()) {
            val title = decodeHtml(titleMatcher.group(1) ?: "")
            val snippet = if (snippetMatcher.find()) decodeHtml(snippetMatcher.group(1) ?: "") else "No description"
            val url = if (urlMatcher.find()) decodeHtml(urlMatcher.group(1) ?: "") else "No URL"

            output.append("${count + 1}. $title\n")
            output.append("   $snippet\n")
            output.append("   URL: $url\n\n")

            count++
        }

        return count
    }

    /**
     * Decode common HTML entities and strip remaining tags.
     */
    private fun decodeHtml(text: String): String {
        var decoded = text
            .replace("<[^>]+>".toRegex(), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#x27;", "'")
            .replace("&mdash;", "\u2014")
            .replace("&ndash;", "\u2013")
            .replace("&hellip;", "\u2026")

        // Decode numeric HTML entities (&#NNN;)
        val numericPattern = Pattern.compile("&#(\\d+);")
        var matcher = numericPattern.matcher(decoded)
        val sb = StringBuffer()
        while (matcher.find()) {
            val codePoint = matcher.group(1)?.toIntOrNull() ?: 0
            matcher.appendReplacement(sb, String(charArrayOf(codePoint.toChar())))
        }
        matcher.appendTail(sb)
        decoded = sb.toString()

        // Decode hex HTML entities (&#xHHHH;)
        val hexPattern = Pattern.compile("&#x([0-9a-fA-F]+);")
        matcher = hexPattern.matcher(decoded)
        val sb2 = StringBuffer()
        while (matcher.find()) {
            val codePoint = matcher.group(1)?.toInt(16) ?: 0
            matcher.appendReplacement(sb2, String(charArrayOf(codePoint.toChar())))
        }
        matcher.appendTail(sb2)

        return sb2.toString().trim()
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        context = null
        cache.clear()
    }
}
