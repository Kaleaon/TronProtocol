package com.tronprotocol.app.plugins

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BriefContentExtractorTest {

    private lateinit var extractor: BriefContentExtractor

    @Before
    fun setUp() {
        extractor = BriefContentExtractor()
    }

    // ── Content Type Detection ──────────────────────────────────────

    @Test
    fun testDetectWebpage() {
        assertEquals(BriefContentExtractor.ContentType.WEBPAGE, extractor.detectType("https://example.com/article"))
    }

    @Test
    fun testDetectYouTubeVideo() {
        assertEquals(BriefContentExtractor.ContentType.VIDEO, extractor.detectType("https://www.youtube.com/watch?v=abc123"))
    }

    @Test
    fun testDetectYoutubeShortUrl() {
        assertEquals(BriefContentExtractor.ContentType.VIDEO, extractor.detectType("https://youtu.be/abc123"))
    }

    @Test
    fun testDetectVimeo() {
        assertEquals(BriefContentExtractor.ContentType.VIDEO, extractor.detectType("https://vimeo.com/123456"))
    }

    @Test
    fun testDetectMediaExtension() {
        assertEquals(BriefContentExtractor.ContentType.VIDEO, extractor.detectType("https://example.com/video.mp4"))
        assertEquals(BriefContentExtractor.ContentType.VIDEO, extractor.detectType("https://example.com/clip.webm"))
    }

    @Test
    fun testDetectPdf() {
        assertEquals(BriefContentExtractor.ContentType.PDF, extractor.detectType("https://example.com/paper.pdf"))
    }

    @Test
    fun testDetectReddit() {
        assertEquals(BriefContentExtractor.ContentType.REDDIT, extractor.detectType("https://www.reddit.com/r/android/comments/abc"))
        assertEquals(BriefContentExtractor.ContentType.REDDIT, extractor.detectType("https://old.reddit.com/r/test"))
    }

    @Test
    fun testDetectGitHub() {
        assertEquals(BriefContentExtractor.ContentType.GITHUB, extractor.detectType("https://github.com/user/repo"))
    }

    @Test
    fun testDetectUnknownForInvalidUrl() {
        assertEquals(BriefContentExtractor.ContentType.UNKNOWN, extractor.detectType("not a url"))
    }

    // ── HTML Title Extraction ───────────────────────────────────────

    @Test
    fun testExtractTitle() {
        val html = "<html><head><title>My Page Title</title></head><body>content</body></html>"
        assertEquals("My Page Title", extractor.extractTitle(html))
    }

    @Test
    fun testExtractTitleWithEntities() {
        val html = "<html><head><title>Tom &amp; Jerry</title></head><body></body></html>"
        assertEquals("Tom & Jerry", extractor.extractTitle(html))
    }

    @Test
    fun testExtractTitleMissing() {
        val html = "<html><head></head><body>content</body></html>"
        assertEquals("", extractor.extractTitle(html))
    }

    // ── HTML Stripping ──────────────────────────────────────────────

    @Test
    fun testStripSimpleHtml() {
        val html = "<html><body><p>Hello world</p></body></html>"
        val text = extractor.stripHtmlToText(html)
        assertTrue(text.contains("Hello world"))
    }

    @Test
    fun testStripScriptTags() {
        val html = "<html><body><p>Content</p><script>alert('xss')</script></body></html>"
        val text = extractor.stripHtmlToText(html)
        assertTrue(text.contains("Content"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("script"))
    }

    @Test
    fun testStripStyleTags() {
        val html = "<html><body><style>.foo{color:red}</style><p>Visible text</p></body></html>"
        val text = extractor.stripHtmlToText(html)
        assertTrue(text.contains("Visible text"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun testStripNavAndFooter() {
        val html = "<html><body><nav>Menu items</nav><article>Main content here</article><footer>Footer</footer></body></html>"
        val text = extractor.stripHtmlToText(html)
        assertTrue(text.contains("Main content here"))
        assertFalse(text.contains("Menu items"))
        assertFalse(text.contains("Footer"))
    }

    @Test
    fun testDecodeHtmlEntities() {
        val html = "<p>5 &gt; 3 &amp; 2 &lt; 4</p>"
        val text = extractor.stripHtmlToText(html)
        assertTrue(text.contains("5 > 3 & 2 < 4"))
    }

    // ── Text Chunking ───────────────────────────────────────────────

    @Test
    fun testTextToChunksBasic() {
        val text = "This is a paragraph that is long enough to pass the minimum length filter.\n" +
                "This is another paragraph with sufficient length for chunking purposes."
        val chunks = extractor.textToChunks(text)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
    }

    @Test
    fun testTextToChunksFiltersShortLines() {
        val text = "Short\nAnother short\nThis is a paragraph that is long enough to pass the minimum length filter."
        val chunks = extractor.textToChunks(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("long enough"))
    }

    @Test
    fun testTextToChunksEmpty() {
        val chunks = extractor.textToChunks("")
        assertTrue(chunks.isEmpty())
    }

    // ── Text Cleaning ───────────────────────────────────────────────

    @Test
    fun testCleanTextCollapsesWhitespace() {
        val text = "Hello     world    test"
        val cleaned = extractor.cleanText(text)
        assertEquals("Hello world test", cleaned)
    }

    @Test
    fun testCleanTextCollapseNewlines() {
        val text = "Para 1\n\n\n\n\nPara 2"
        val cleaned = extractor.cleanText(text)
        assertEquals("Para 1\n\nPara 2", cleaned)
    }

    @Test
    fun testCleanTextSplitsJammedBullets() {
        val text = "End of sentence. - Next bullet point"
        val cleaned = extractor.cleanText(text)
        assertTrue(cleaned.contains("End of sentence.\n- Next bullet point"))
    }
}
