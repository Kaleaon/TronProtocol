package com.tronprotocol.app.plugins

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Extracts and cleans web content from URLs for the Brief plugin.
 *
 * Reimplements the core extraction logic from the Brief Python library
 * (https://github.com/aulesy/brief) as a native Android/Kotlin component.
 *
 * Supports webpage content extraction via HTML tag stripping with
 * script/style removal, paragraph chunking, and text cleanup.
 */
class BriefContentExtractor {

    companion object {
        private const val TAG = "BriefContentExtractor"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB
        private const val MIN_CHUNK_LENGTH = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Content type detected from a URI.
     */
    enum class ContentType {
        WEBPAGE, VIDEO, REDDIT, GITHUB, PDF, UNKNOWN
    }

    /**
     * A chunk of extracted text content.
     */
    data class ContentChunk(
        val text: String,
        val index: Int
    )

    /**
     * Result of content extraction.
     */
    data class ExtractionResult(
        val contentType: ContentType,
        val uri: String,
        val chunks: List<ContentChunk>,
        val title: String
    )

    private val videoHosts = setOf(
        "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com"
    )
    private val redditHosts = setOf("reddit.com", "old.reddit.com", "np.reddit.com")
    private val githubHosts = setOf("github.com")

    /**
     * Detect content type from a URI, matching Brief's detect_type logic.
     */
    fun detectType(uri: String): ContentType {
        val url = try { URL(uri) } catch (e: Exception) { return ContentType.UNKNOWN }
        val pathLower = url.path?.lowercase() ?: ""
        val host = url.host?.lowercase() ?: ""

        if (pathLower.endsWith(".pdf")) return ContentType.PDF

        val mediaExtensions = setOf(".mp4", ".webm", ".m3u8", ".mpd", ".mov", ".avi", ".mkv")
        for (ext in mediaExtensions) {
            if (pathLower.endsWith(ext)) return ContentType.VIDEO
        }

        if (videoHosts.any { host.contains(it) }) return ContentType.VIDEO
        if (redditHosts.any { host.contains(it) }) return ContentType.REDDIT
        if (githubHosts.any { host.contains(it) }) return ContentType.GITHUB

        return ContentType.WEBPAGE
    }

    /**
     * Extract content from a URL. Currently supports webpages.
     * Video, Reddit, GitHub, and PDF return type-specific messages.
     */
    fun extract(uri: String): ExtractionResult {
        val contentType = detectType(uri)

        return when (contentType) {
            ContentType.WEBPAGE -> extractWebpage(uri)
            ContentType.VIDEO -> ExtractionResult(
                contentType, uri,
                listOf(ContentChunk("[Video content at $uri — extraction requires captions API]", 0)),
                "Video"
            )
            ContentType.REDDIT -> extractWebpage(uri) // Reddit pages work as webpages
            ContentType.GITHUB -> extractWebpage(uri) // GitHub READMEs work as webpages
            ContentType.PDF -> ExtractionResult(
                contentType, uri,
                listOf(ContentChunk("[PDF content at $uri — binary extraction not supported on-device]", 0)),
                "PDF"
            )
            ContentType.UNKNOWN -> ExtractionResult(
                contentType, uri, emptyList(), ""
            )
        }
    }

    /**
     * Fetch a webpage and extract its text content.
     * Mirrors Brief's fallback chain: fetch HTML, strip tags, chunk into paragraphs.
     */
    private fun extractWebpage(uri: String): ExtractionResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(uri)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Accept-Encoding", "identity") // no compression on Android

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorMsg = when (responseCode) {
                    404 -> "URL not found (404) — '$uri' does not exist"
                    in 401..403 -> "URL blocked ($responseCode) — requires authentication or is paywalled"
                    429 -> "URL rate limited (429) — too many requests"
                    else -> "HTTP error $responseCode fetching $uri"
                }
                return ExtractionResult(ContentType.WEBPAGE, uri,
                    listOf(ContentChunk(errorMsg, 0)), "")
            }

            val reader = BufferedReader(
                InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
            )
            val html = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                html.append(line).append("\n")
                if (html.length > MAX_RESPONSE_BYTES) break
            }
            reader.close()

            val rawHtml = html.toString()
            val title = extractTitle(rawHtml)
            val text = stripHtmlToText(rawHtml)

            if (text.length < 50) {
                return ExtractionResult(ContentType.WEBPAGE, uri, emptyList(), title)
            }

            val chunks = textToChunks(cleanText(text))
            Log.d(TAG, "Extracted ${chunks.size} chunks from $uri")
            return ExtractionResult(ContentType.WEBPAGE, uri, chunks, title)

        } catch (e: Exception) {
            Log.w(TAG, "Extraction failed for $uri: ${e.message}")
            return ExtractionResult(ContentType.WEBPAGE, uri,
                listOf(ContentChunk("Extraction failed: ${e.message}", 0)), "")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extract page title from HTML.
     */
    internal fun extractTitle(html: String): String {
        val titlePattern = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = titlePattern.matcher(html)
        return if (matcher.find()) {
            decodeHtmlEntities(matcher.group(1)?.trim() ?: "")
        } else ""
    }

    /**
     * Strip HTML tags to get plain text.
     * Matches Brief's tag-stripping fallback approach:
     * 1. Remove script and style blocks entirely
     * 2. Replace block-level tags with newlines
     * 3. Strip remaining tags
     * 4. Decode HTML entities
     * 5. Collapse whitespace
     */
    internal fun stripHtmlToText(html: String): String {
        var text = html

        // Remove script blocks
        text = text.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")

        // Remove style blocks
        text = text.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")

        // Remove HTML comments
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Remove head section
        text = text.replace(Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL), "")

        // Remove nav, footer, header, aside (non-content elements)
        for (tag in listOf("nav", "footer", "header", "aside")) {
            text = text.replace(Regex("<$tag[^>]*>.*?</$tag>", RegexOption.DOT_MATCHES_ALL), "\n")
        }

        // Replace block-level tags with newlines
        val blockTags = "p|div|br|hr|h[1-6]|li|tr|td|th|article|section|blockquote|pre|table"
        text = text.replace(Regex("</?($blockTags)[^>]*>", RegexOption.IGNORE_CASE), "\n")

        // Strip remaining tags
        text = text.replace(Regex("<[^>]+>"), " ")

        // Decode HTML entities
        text = decodeHtmlEntities(text)

        // Collapse whitespace within lines (preserve newlines)
        text = text.replace(Regex("[^\\S\\n]+"), " ")

        // Collapse 3+ newlines into 2
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        return text.trim()
    }

    /**
     * Clean extracted text, matching Brief's _clean_text.
     */
    internal fun cleanText(text: String): String {
        var cleaned = text

        // Ensure space before markdown links
        cleaned = cleaned.replace(Regex("(\\w)\\[([^]]+)]\\("), "$1 [$2](")
        // Ensure space after markdown links
        cleaned = cleaned.replace(Regex("]\\(([^)]+)\\)(\\w)"), "]($1) $2")

        // Split jammed bullets
        cleaned = cleaned.replace(Regex("([.!?])\\s*-\\s+"), "$1\n- ")

        // Collapse multiple spaces (not newlines)
        cleaned = cleaned.replace(Regex("[^\\S\\n]+"), " ")

        // Collapse 3+ newlines into 2
        cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")

        return cleaned.trim()
    }

    /**
     * Split text into paragraph-level chunks, matching Brief's _text_to_chunks.
     */
    internal fun textToChunks(text: String): List<ContentChunk> {
        val paragraphs = text.split("\n")
            .map { it.trim() }
            .filter { it.length >= MIN_CHUNK_LENGTH }

        return paragraphs.mapIndexed { index, para ->
            ContentChunk(para, index)
        }
    }

    /**
     * Decode common HTML entities.
     */
    private fun decodeHtmlEntities(text: String): String {
        var decoded = text
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

        // Decode numeric entities &#NNN;
        val numericPattern = Pattern.compile("&#(\\d+);")
        var matcher = numericPattern.matcher(decoded)
        val sb = StringBuffer()
        while (matcher.find()) {
            val codePoint = matcher.group(1)?.toIntOrNull() ?: 0
            if (codePoint in 1..0xFFFF) {
                matcher.appendReplacement(sb, String(charArrayOf(codePoint.toChar())))
            }
        }
        matcher.appendTail(sb)
        decoded = sb.toString()

        // Decode hex entities &#xHHHH;
        val hexPattern = Pattern.compile("&#x([0-9a-fA-F]+);")
        matcher = hexPattern.matcher(decoded)
        val sb2 = StringBuffer()
        while (matcher.find()) {
            val codePoint = matcher.group(1)?.toInt(16) ?: 0
            if (codePoint in 1..0xFFFF) {
                matcher.appendReplacement(sb2, String(charArrayOf(codePoint.toChar())))
            }
        }
        matcher.appendTail(sb2)

        return sb2.toString()
    }
}
