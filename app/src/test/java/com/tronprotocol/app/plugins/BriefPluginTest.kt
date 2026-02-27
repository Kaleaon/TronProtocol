package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BriefPluginTest {

    private lateinit var plugin: BriefPlugin

    @Before
    fun setUp() {
        plugin = BriefPlugin()
    }

    // ── Plugin Interface ────────────────────────────────────────────

    @Test
    fun testPluginId() {
        assertEquals("brief_content", plugin.id)
    }

    @Test
    fun testPluginName() {
        assertEquals("Brief Content", plugin.name)
    }

    @Test
    fun testPluginEnabled() {
        assertTrue(plugin.isEnabled)
        plugin.isEnabled = false
        assertFalse(plugin.isEnabled)
    }

    @Test
    fun testPluginDescriptionContainsCommands() {
        val desc = plugin.description
        assertTrue(desc.contains("brief|url"))
        assertTrue(desc.contains("batch|"))
        assertTrue(desc.contains("compare|"))
        assertTrue(desc.contains("ingest|"))
    }

    // ── Command Parsing ─────────────────────────────────────────────

    @Test
    fun testEmptyInputReturnsError() {
        val result = plugin.execute("")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("No command"))
    }

    @Test
    fun testBlankInputReturnsError() {
        val result = plugin.execute("   ")
        assertFalse(result.isSuccess)
    }

    @Test
    fun testUnknownCommandReturnsError() {
        val result = plugin.execute("foo|bar")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Unknown command"))
    }

    @Test
    fun testBriefMissingUrlReturnsError() {
        val result = plugin.execute("brief")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Usage"))
    }

    @Test
    fun testBriefEmptyUrlReturnsError() {
        val result = plugin.execute("brief|")
        assertFalse(result.isSuccess)
    }

    @Test
    fun testBatchMissingUrlsReturnsError() {
        val result = plugin.execute("batch")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Usage"))
    }

    @Test
    fun testBatchEmptyUrlsReturnsError() {
        val result = plugin.execute("batch|")
        assertFalse(result.isSuccess)
    }

    @Test
    fun testCompareMissingUrlsReturnsError() {
        val result = plugin.execute("compare")
        assertFalse(result.isSuccess)
    }

    @Test
    fun testCompareNeedsTwoUrls() {
        val result = plugin.execute("compare|https://example.com")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("at least 2"))
    }

    @Test
    fun testCheckMissingUrlReturnsError() {
        val result = plugin.execute("check")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Usage"))
    }

    @Test
    fun testIngestMissingUrlReturnsError() {
        val result = plugin.execute("ingest")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Usage"))
    }

    // ── List and Stats (no initialization needed) ───────────────────

    @Test
    fun testListEmptyCache() {
        val result = plugin.execute("list")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("No briefs cached"))
    }

    @Test
    fun testStats() {
        val result = plugin.execute("stats")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Total briefs created"))
        assertTrue(result.data!!.contains("Cache hits"))
        assertTrue(result.data!!.contains("LLM calls"))
    }

    @Test
    fun testClearCache() {
        val result = plugin.execute("clear_cache")
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Cleared"))
    }

    // ── Heuristic Summary ───────────────────────────────────────────

    @Test
    fun testHeuristicSummaryDepth0() {
        val text = "This is the first sentence of a longer text. " +
                "This is a second sentence that adds more detail. " +
                "And a third sentence for completeness."
        val summary = plugin.heuristicSummary(text, 0)
        // Depth 0 should be short (headline)
        assertTrue(summary.length <= 170)
    }

    @Test
    fun testHeuristicSummaryDepth1() {
        val text = "First important point about this topic. " +
                "Second key detail worth mentioning. " +
                "Third aspect of the discussion here. " +
                "Fourth elaboration on the subject."
        val summary = plugin.heuristicSummary(text, 1)
        assertTrue(summary.length <= 510)
    }

    @Test
    fun testHeuristicSummaryDepth2() {
        val text = "Detailed analysis paragraph one. " +
                "Detailed analysis paragraph two. " +
                "Detailed analysis paragraph three. " +
                "Detailed analysis paragraph four. " +
                "Detailed analysis paragraph five."
        val summary = plugin.heuristicSummary(text, 2)
        assertTrue(summary.length <= 2010)
    }

    @Test
    fun testHeuristicSummaryShortText() {
        val text = "Very short input"
        val summary = plugin.heuristicSummary(text, 1)
        assertEquals("Very short input", summary)
    }

    // ── Hash Function ───────────────────────────────────────────────

    @Test
    fun testHashUrlDeterministic() {
        val hash1 = plugin.hashUrl("https://example.com/article")
        val hash2 = plugin.hashUrl("https://example.com/article")
        assertEquals(hash1, hash2)
    }

    @Test
    fun testHashUrlDifferentForDifferentUrls() {
        val hash1 = plugin.hashUrl("https://example.com/article1")
        val hash2 = plugin.hashUrl("https://example.com/article2")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun testHashUrlLength() {
        val hash = plugin.hashUrl("https://example.com/some/path")
        assertEquals(16, hash.length) // 8 bytes = 16 hex chars
    }

    // ── Execution Time ──────────────────────────────────────────────

    @Test
    fun testExecutionTimeReported() {
        val result = plugin.execute("stats")
        assertTrue(result.executionTimeMs >= 0)
    }

    // ── Registry Integration ────────────────────────────────────────

    @Test
    fun testPluginRegisteredInRegistry() {
        val config = PluginRegistry.configs.find { it.id == "brief_content" }
        assertNotNull("BriefPlugin should be registered in PluginRegistry", config)
        assertEquals(155, config!!.startupPriority)
        assertTrue(config.defaultCapabilities.contains(Capability.NETWORK_OUTBOUND))
        assertTrue(config.defaultCapabilities.contains(Capability.HTTP_REQUEST))
        assertTrue(config.defaultCapabilities.contains(Capability.MEMORY_WRITE))
    }

    @Test
    fun testPluginFactoryCreatesInstance() {
        val config = PluginRegistry.configs.find { it.id == "brief_content" }!!
        val instance = config.factory()
        assertTrue(instance is BriefPlugin)
        assertEquals("brief_content", instance.id)
    }
}
