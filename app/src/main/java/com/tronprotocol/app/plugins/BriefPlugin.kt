package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tronprotocol.app.guidance.AnthropicApiClient
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Brief Plugin — on-device content compression for AI agents.
 *
 * Native Kotlin reimplementation of the Brief library (https://github.com/aulesy/brief).
 * Extracts, summarizes, and caches web content with depth-aware detail levels,
 * all running locally on the Android device.
 *
 * Flow:
 * 1. Extract content from URL (HTML fetch + tag stripping)
 * 2. Summarize via on-device LLM or cloud API (depth-aware prompts)
 * 3. Cache results at two levels (source extraction + per-query summary)
 * 4. Optionally ingest into RAG memory store
 *
 * Commands:
 *   brief|url|query|depth       — Summarize a URL (depth 0-2, default 1)
 *   batch|url1,url2|query|depth — Batch-brief multiple URLs
 *   compare|url1,url2|query|depth — Compare multiple sources
 *   check|url                   — Check if a brief exists in cache
 *   list                        — List all cached briefs
 *   stats                       — Show cache statistics
 *   ingest|url|query|depth      — Brief a URL and ingest into RAG store
 *   clear_cache                 — Clear all cached briefs
 */
class BriefPlugin : Plugin {

    companion object {
        private const val TAG = "BriefPlugin"
        private const val ID = "brief_content"
        private const val PREFS_NAME = "brief_plugin_prefs"
        private const val TIMEOUT_MS = 30000

        // Cache settings
        private const val SOURCE_CACHE_PREFIX = "brief_source_"
        private const val QUERY_CACHE_PREFIX = "brief_query_"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes in-memory
        private const val MAX_MEMORY_CACHE = 50

        // Depth-aware summarization prompts (from Brief's summarizer.py)
        private val DEPTH_PROMPTS = mapOf(
            0 to DepthConfig(
                system = "You answer in exactly ONE sentence. No preamble, no key points. " +
                        "Respond with a single concise sentence.",
                userTemplate = "In one sentence, does this content cover: %s?\n\n%s",
                userGeneric = "In one sentence, what is this about?\n\n%s",
                maxTokens = 100
            ),
            1 to DepthConfig(
                system = "You summarize content for AI agents. Be factual, direct, concise. " +
                        "Lead with the answer to the question, not a generic description. " +
                        "Provide a 2-3 sentence summary followed by key points as bullet items.",
                userTemplate = "Answer this question about the content: %s\n\n%s",
                userGeneric = "Summarize this content:\n\n%s",
                maxTokens = 400
            ),
            2 to DepthConfig(
                system = "You are a research analyst. Provide a detailed, thorough analysis. " +
                        "Include specifics: exact numbers, evidence, nuances, trade-offs, " +
                        "and anything someone would need to fully understand this topic.",
                userTemplate = "Deep dive into this topic: %s\n\nAnalyze thoroughly:\n\n%s",
                userGeneric = "Provide a detailed analysis of this content:\n\n%s",
                maxTokens = 1000
            )
        )

        private val COMPARISON_PROMPTS = mapOf(
            0 to "Compare these sources in one sentence. Be direct.",
            1 to "You are a research analyst comparing multiple sources. " +
                    "Identify where sources agree, where they differ, and note unique insights from each. " +
                    "Be direct and specific.",
            2 to "You are a research analyst comparing multiple sources in detail. " +
                    "Provide a thorough comparative analysis: where sources agree, " +
                    "where they disagree, unique insights from each, and any gaps. " +
                    "Be specific with examples and details from each source."
        )
    }

    private data class DepthConfig(
        val system: String,
        val userTemplate: String,
        val userGeneric: String,
        val maxTokens: Int
    )

    private data class CachedEntry(val data: String, val timestamp: Long)

    private var context: Context? = null
    private lateinit var prefs: SharedPreferences
    private var secureStorage: SecureStorage? = null
    private val extractor = BriefContentExtractor()
    private val memoryCache = ConcurrentHashMap<String, CachedEntry>()

    // LLM integration — lazily initialized
    private var onDeviceLLMManager: OnDeviceLLMManager? = null
    private var anthropicClient: AnthropicApiClient? = null
    private var ragStore: RAGStore? = null
    private var ragBridge: BriefRAGBridge? = null

    // Stats
    private var totalBriefs = 0
    private var cacheHits = 0
    private var llmCalls = 0

    override val id: String = ID

    override val name: String = "Brief Content"

    override val description: String =
        "Extract, summarize, and cache web content with depth-aware detail levels. " +
                "Commands: brief|url|query|depth, batch|url1,url2|query|depth, " +
                "compare|url1,url2|query|depth, check|url, list, stats, " +
                "ingest|url|query|depth, clear_cache"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isBlank()) {
                return PluginResult.error("No command provided. Use: brief|url|query|depth", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "brief" -> executeBrief(parts, start)
                "batch" -> executeBatch(parts, start)
                "compare" -> executeCompare(parts, start)
                "check" -> executeCheck(parts, start)
                "list" -> executeList(start)
                "stats" -> executeStats(start)
                "ingest" -> executeIngest(parts, start)
                "clear_cache" -> executeClearCache(start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Brief failed: ${e.message}", elapsed(start))
        }
    }

    // ── Command Implementations ─────────────────────────────────────

    private fun executeBrief(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: brief|url|query|depth", elapsed(start))
        }

        val url = parts[1].trim().trimEnd(',', ';')
        val query = if (parts.size > 2) parts[2].trim() else "summarize this content"
        val depth = if (parts.size > 3) parts[3].trim().toIntOrNull()?.coerceIn(0, 2) ?: 1 else 1

        val result = briefUrl(url, query, depth)
        return PluginResult.success(result, elapsed(start))
    }

    private fun executeBatch(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: batch|url1,url2,...|query|depth", elapsed(start))
        }

        val urls = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            return PluginResult.error("No valid URLs provided", elapsed(start))
        }

        val query = if (parts.size > 2) parts[2].trim() else "summarize this content"
        val depth = if (parts.size > 3) parts[3].trim().toIntOrNull()?.coerceIn(0, 2) ?: 0 else 0

        val results = StringBuilder()
        results.append("Batch brief: ${urls.size} URLs (depth=$depth)\n\n")

        for ((i, url) in urls.withIndex()) {
            results.append("── Source ${i + 1}: $url ──\n")
            results.append(briefUrl(url, query, depth))
            results.append("\n\n")
        }

        return PluginResult.success(results.toString().trim(), elapsed(start))
    }

    private fun executeCompare(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: compare|url1,url2,...|query|depth", elapsed(start))
        }

        val urls = parts[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (urls.size < 2) {
            return PluginResult.error("Compare requires at least 2 URLs", elapsed(start))
        }

        val query = if (parts.size > 2) parts[2].trim() else "summarize this content"
        val depth = if (parts.size > 3) parts[3].trim().toIntOrNull()?.coerceIn(0, 2) ?: 1 else 1

        // Check comparison cache
        val compCacheKey = comparisonCacheKey(urls, query, depth)
        val cached = lookupMemoryCache(compCacheKey)
        if (cached != null) {
            cacheHits++
            return PluginResult.success(cached, elapsed(start))
        }

        // Brief each URL
        val briefTexts = mutableListOf<String>()
        for (url in urls) {
            briefTexts.add(briefUrl(url, query, depth))
        }

        // Synthesize comparison
        val synthesis = synthesizeComparison(briefTexts, query, depth)

        val result = StringBuilder()
        result.append("═══ COMPARISON ${"═".repeat(45)}\n")
        result.append("$query\n")
        result.append("Sources: ${urls.size} | Depth: $depth\n\n")
        result.append("─── ANALYSIS ${"─".repeat(47)}\n")
        result.append(synthesis ?: "Comparison synthesis unavailable (no LLM). See individual briefs.")
        result.append("\n\n─── SOURCES ${"─".repeat(48)}\n")
        for ((i, url) in urls.withIndex()) {
            result.append("→ source ${i + 1}: $url\n")
        }

        val resultText = result.toString()
        storeMemoryCache(compCacheKey, resultText)
        return PluginResult.success(resultText, elapsed(start))
    }

    private fun executeCheck(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: check|url", elapsed(start))
        }

        val url = parts[1].trim()
        val sourceKey = sourceCacheKey(url)

        val hasPersisted = secureStorage?.retrieve(sourceKey) != null
        val memoryKeys = memoryCache.keys().toList().filter { it.startsWith(QUERY_CACHE_PREFIX) && it.contains(hashUrl(url)) }

        if (!hasPersisted && memoryKeys.isEmpty()) {
            return PluginResult.success("No briefs exist for $url", elapsed(start))
        }

        val sb = StringBuilder("Briefs for $url:\n")
        if (hasPersisted) {
            sb.append("  • Source data cached (extracted content)\n")
        }
        sb.append("  • ${memoryKeys.size} query-specific brief(s) in memory\n")
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeList(start: Long): PluginResult {
        val entries = memoryCache.entries
            .filter { it.key.startsWith(QUERY_CACHE_PREFIX) }
            .sortedByDescending { it.value.timestamp }

        if (entries.isEmpty()) {
            return PluginResult.success("No briefs cached. Use: brief|url|query|depth", elapsed(start))
        }

        val sb = StringBuilder("Cached briefs (${entries.size}):\n\n")
        for ((i, entry) in entries.withIndex()) {
            val preview = entry.value.data.take(100).replace("\n", " ")
            sb.append("  ${i + 1}. $preview...\n")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeStats(start: Long): PluginResult {
        val stats = "Brief Plugin Statistics:\n" +
                "  Total briefs created: $totalBriefs\n" +
                "  Cache hits: $cacheHits\n" +
                "  LLM calls: $llmCalls\n" +
                "  Memory cache entries: ${memoryCache.size}\n" +
                "  On-device LLM: ${if (onDeviceLLMManager?.isReady == true) "ready" else "unavailable"}\n" +
                "  Cloud API: ${if (anthropicClient != null) "configured" else "unavailable"}"
        return PluginResult.success(stats, elapsed(start))
    }

    private fun executeIngest(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: ingest|url|query|depth", elapsed(start))
        }

        val url = parts[1].trim().trimEnd(',', ';')
        val query = if (parts.size > 2) parts[2].trim() else "summarize this content"
        val depth = if (parts.size > 3) parts[3].trim().toIntOrNull()?.coerceIn(0, 2) ?: 1 else 1

        val briefText = briefUrl(url, query, depth)
        val bridge = ragBridge
        if (bridge == null) {
            return PluginResult.success(
                "$briefText\n\n[RAG ingest skipped: RAG store not available]",
                elapsed(start)
            )
        }

        val chunkId = bridge.ingestBriefResult(url, query, depth, briefText)
        return PluginResult.success(
            "$briefText\n\n[Ingested into RAG store: chunk=$chunkId]",
            elapsed(start)
        )
    }

    private fun executeClearCache(start: Long): PluginResult {
        val count = memoryCache.size
        memoryCache.clear()
        cacheHits = 0
        return PluginResult.success("Cleared $count cached briefs", elapsed(start))
    }

    // ── Core Brief Logic ────────────────────────────────────────────

    /**
     * Main entry point: get a rendered brief for a URL.
     *
     * Matches Brief's service.py flow:
     * 1. Check query cache → return cached
     * 2. Check source cache → summarize with LLM
     * 3. Extract → save source → summarize → save query brief
     */
    private fun briefUrl(url: String, query: String, depth: Int): String {
        // Check query cache first
        val queryCacheKey = queryCacheKey(url, query, depth)
        val cached = lookupMemoryCache(queryCacheKey)
        if (cached != null && depth > 0) {
            cacheHits++
            Log.d(TAG, "Cache hit: $url (query=$query, depth=$depth)")
            return cached
        }

        // Get chunks: from source cache or fresh extraction
        val chunks: List<BriefContentExtractor.ContentChunk>
        val contentType: BriefContentExtractor.ContentType
        val title: String

        val sourceCacheKey = sourceCacheKey(url)
        val cachedSource = lookupSourceCache(sourceCacheKey)
        if (cachedSource != null) {
            chunks = cachedSource.chunks
            contentType = cachedSource.contentType
            title = cachedSource.title
            Log.d(TAG, "Source cache hit: $url (${chunks.size} chunks)")
        } else {
            val extraction = extractor.extract(url)
            chunks = extraction.chunks
            contentType = extraction.contentType
            title = extraction.title

            if (chunks.isNotEmpty()) {
                saveSourceCache(sourceCacheKey, extraction)
            }
        }

        if (chunks.isEmpty()) {
            return "Could not extract content from $url"
        }

        // Summarize with LLM
        val transcript = chunks.joinToString(" ") { it.text }
        val summary = summarize(transcript, query, depth)
        totalBriefs++

        // Build rendered brief
        val rendered = renderBrief(url, query, summary, contentType, depth, title)

        // Cache the result (depth > 0 only, depth 0 is triage)
        if (depth > 0) {
            storeMemoryCache(queryCacheKey, rendered)
        }

        return rendered
    }

    /**
     * Generate a depth-aware, query-focused summary.
     * Routes through on-device LLM first, falls back to cloud API,
     * then to heuristic extraction if no LLM is available.
     */
    private fun summarize(transcript: String, query: String, depth: Int): String {
        val config = DEPTH_PROMPTS[depth] ?: DEPTH_PROMPTS[1]!!

        // Truncate transcript to fit context window (~4 chars/token, ~8K tokens max for mobile)
        val maxChars = 32000
        val truncatedTranscript = if (transcript.length > maxChars) {
            transcript.substring(0, maxChars).substringBeforeLast(' ') + "..."
        } else transcript

        val prompt = if (query != "summarize this content") {
            String.format(config.userTemplate, query, truncatedTranscript)
        } else {
            String.format(config.userGeneric, truncatedTranscript)
        }

        val fullPrompt = "${config.system}\n\n$prompt"

        // Tier 1: On-device LLM
        val llm = onDeviceLLMManager
        if (llm != null && llm.isReady) {
            try {
                val result = llm.generate(fullPrompt)
                if (result.success && !result.text.isNullOrBlank()) {
                    llmCalls++
                    Log.d(TAG, "On-device LLM summary: ${result.tokensGenerated} tokens, ${result.latencyMs}ms")
                    return result.text.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device LLM failed: ${e.message}")
            }
        }

        // Tier 2: Cloud API (Anthropic)
        val apiKey = secureStorage?.retrieve("anthropic_api_key")
        val client = anthropicClient
        if (client != null && !apiKey.isNullOrBlank()) {
            try {
                val cloudResult = client.createGuidance(
                    apiKey, AnthropicApiClient.MODEL_SONNET, fullPrompt, config.maxTokens
                )
                llmCalls++
                Log.d(TAG, "Cloud API summary generated")
                return cloudResult.trim()
            } catch (e: Exception) {
                Log.w(TAG, "Cloud API failed: ${e.message}")
            }
        }

        // Tier 3: Heuristic fallback (no LLM available)
        Log.d(TAG, "Using heuristic summary fallback")
        return heuristicSummary(transcript, depth)
    }

    /**
     * Synthesize a comparison across multiple brief texts.
     */
    private fun synthesizeComparison(briefs: List<String>, query: String, depth: Int): String? {
        val sourceBlock = StringBuilder()
        for ((i, text) in briefs.withIndex()) {
            sourceBlock.append("--- source ${i + 1} ---\n${text.trim()}\n\n")
        }

        val systemPrompt = COMPARISON_PROMPTS[depth] ?: COMPARISON_PROMPTS[1]!!
        val fullPrompt = "$systemPrompt\n\nCompare these sources on: $query\n\n$sourceBlock"

        // Try on-device LLM
        val llm = onDeviceLLMManager
        if (llm != null && llm.isReady) {
            try {
                val result = llm.generate(fullPrompt)
                if (result.success && !result.text.isNullOrBlank()) {
                    llmCalls++
                    return result.text.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device comparison LLM failed: ${e.message}")
            }
        }

        // Try cloud API
        val apiKey = secureStorage?.retrieve("anthropic_api_key")
        val client = anthropicClient
        if (client != null && !apiKey.isNullOrBlank()) {
            try {
                val maxTokens = when (depth) { 0 -> 150; 2 -> 2000; else -> 1000 }
                val cloudResult = client.createGuidance(
                    apiKey, AnthropicApiClient.MODEL_SONNET, fullPrompt, maxTokens
                )
                llmCalls++
                return cloudResult.trim()
            } catch (e: Exception) {
                Log.w(TAG, "Cloud comparison failed: ${e.message}")
            }
        }

        return null
    }

    /**
     * Heuristic summary when no LLM is available.
     * Matches Brief's _heuristic_summary fallback.
     */
    internal fun heuristicSummary(text: String, depth: Int): String {
        val sentences = text.split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.length > 20 }

        if (sentences.isEmpty()) return text.take(300)

        return when (depth) {
            0 -> truncateClean(sentences.first(), 160)
            1 -> {
                val summary = sentences.take(3).joinToString(". ") + "."
                truncateClean(summary, 500)
            }
            else -> {
                val summary = sentences.take(8).joinToString(". ") + "."
                truncateClean(summary, 2000)
            }
        }
    }

    /**
     * Render a brief in Brief's output format.
     */
    private fun renderBrief(
        url: String,
        query: String,
        summary: String,
        contentType: BriefContentExtractor.ContentType,
        depth: Int,
        title: String
    ): String {
        val typeName = contentType.name

        if (depth == 0) {
            // Headline format
            val headline = truncateClean(summary, 160)
            return "[$typeName: $url]\n$headline"
        }

        val lines = mutableListOf<String>()
        lines.add("═══ BRIEF ${"═".repeat(50)}")
        if (title.isNotBlank()) lines.add(title)
        lines.add(url)
        lines.add("Query: \"${truncateClean(query, 60)}\" | Type: $typeName | Depth: $depth")
        lines.add("")
        lines.add("─── ANSWER ${"─".repeat(49)}")
        lines.add(summary)

        return lines.joinToString("\n")
    }

    // ── Cache Management ────────────────────────────────────────────

    private fun sourceCacheKey(url: String): String = SOURCE_CACHE_PREFIX + hashUrl(url)

    private fun queryCacheKey(url: String, query: String, depth: Int): String =
        QUERY_CACHE_PREFIX + hashUrl(url) + "_" + hashText(query) + "_d$depth"

    private fun comparisonCacheKey(urls: List<String>, query: String, depth: Int): String {
        val sortedHash = urls.sorted().joinToString(",").let { hashText(it) }
        return "brief_comparison_${sortedHash}_${hashText(query)}_d$depth"
    }

    private fun lookupMemoryCache(key: String): String? {
        val entry = memoryCache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            memoryCache.remove(key)
            return null
        }
        return entry.data
    }

    private fun storeMemoryCache(key: String, data: String) {
        if (memoryCache.size >= MAX_MEMORY_CACHE) {
            val oldest = memoryCache.entries.minByOrNull { it.value.timestamp }
            if (oldest != null) memoryCache.remove(oldest.key)
        }
        memoryCache[key] = CachedEntry(data, System.currentTimeMillis())
    }

    /**
     * Save source extraction to persistent storage (SecureStorage).
     */
    private fun saveSourceCache(key: String, extraction: BriefContentExtractor.ExtractionResult) {
        val storage = secureStorage ?: return
        try {
            val json = JSONObject().apply {
                put("contentType", extraction.contentType.name)
                put("uri", extraction.uri)
                put("title", extraction.title)
                val chunksArray = JSONArray()
                for (chunk in extraction.chunks) {
                    chunksArray.put(JSONObject().apply {
                        put("text", chunk.text)
                        put("index", chunk.index)
                    })
                }
                put("chunks", chunksArray)
                put("created", System.currentTimeMillis())
            }
            storage.store(key, json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save source cache: ${e.message}")
        }
    }

    /**
     * Load source extraction from persistent storage.
     */
    private fun lookupSourceCache(key: String): BriefContentExtractor.ExtractionResult? {
        val storage = secureStorage ?: return null
        try {
            val data = storage.retrieve(key) ?: return null
            val json = JSONObject(data)

            val contentType = try {
                BriefContentExtractor.ContentType.valueOf(json.getString("contentType"))
            } catch (e: Exception) {
                BriefContentExtractor.ContentType.WEBPAGE
            }

            val chunksArray = json.getJSONArray("chunks")
            val chunks = mutableListOf<BriefContentExtractor.ContentChunk>()
            for (i in 0 until chunksArray.length()) {
                val chunkObj = chunksArray.getJSONObject(i)
                chunks.add(BriefContentExtractor.ContentChunk(
                    chunkObj.getString("text"),
                    chunkObj.getInt("index")
                ))
            }

            return BriefContentExtractor.ExtractionResult(
                contentType,
                json.getString("uri"),
                chunks,
                json.optString("title", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load source cache: ${e.message}")
            return null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    internal fun hashUrl(url: String): String = hashText(url)

    private fun hashText(text: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until minOf(8, digest.size)) {
                sb.append(String.format("%02x", digest[i]))
            }
            sb.toString()
        } catch (e: Exception) {
            text.hashCode().toString(16)
        }
    }

    private fun truncateClean(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val cut = text.substring(0, maxLen).substringBeforeLast(' ')
        return cut.trimEnd('.', ',', ';', ':', '!', '?') + "..."
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    // ── Plugin Lifecycle ────────────────────────────────────────────

    override fun initialize(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            secureStorage = SecureStorage(context)
        } catch (e: Exception) {
            Log.w(TAG, "SecureStorage init failed: ${e.message}")
        }

        // Initialize LLM managers
        try {
            onDeviceLLMManager = OnDeviceLLMManager(context)
        } catch (e: Exception) {
            Log.w(TAG, "On-device LLM init failed: ${e.message}")
        }

        try {
            anthropicClient = AnthropicApiClient(10, 2000)
        } catch (e: Exception) {
            Log.w(TAG, "Anthropic client init failed: ${e.message}")
        }

        // Initialize RAG integration
        try {
            ragStore = RAGStore(context, "tronprotocol_ai")
            ragStore?.let { ragBridge = BriefRAGBridge(it) }
        } catch (e: Exception) {
            Log.w(TAG, "RAG store init failed: ${e.message}")
        }
    }

    override fun destroy() {
        onDeviceLLMManager?.shutdown()
        onDeviceLLMManager = null
        anthropicClient = null
        ragStore = null
        ragBridge = null
        secureStorage = null
        context = null
        memoryCache.clear()
    }
}
