package com.tronprotocol.app.llm.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMetadataExtractorTest {

    @Test
    fun extractParameterCountFromModelName() {
        assertEquals("1.5B", ModelMetadataExtractor.extractParameterCount("Qwen2.5-1.5B-Instruct"))
        assertEquals("8B", ModelMetadataExtractor.extractParameterCount("Qwen3-8B-GGUF"))
        assertEquals("0.5B", ModelMetadataExtractor.extractParameterCount("Ruvltra-Claude-Code-0.5b"))
        assertEquals("3B", ModelMetadataExtractor.extractParameterCount("Llama-3.2-3B"))
        assertNull(ModelMetadataExtractor.extractParameterCount("some-model-without-params"))
    }

    @Test
    fun extractQuantizationFromFileName() {
        assertEquals("Q4_K_M", ModelMetadataExtractor.extractQuantization("Qwen3-8B-Q4_K_M.gguf"))
        assertEquals("Q5_K_S", ModelMetadataExtractor.extractQuantization("model-Q5_K_S.gguf"))
        assertEquals("F16", ModelMetadataExtractor.extractQuantization("model-F16.gguf"))
        assertNull(ModelMetadataExtractor.extractQuantization("model.gguf"))
    }

    @Test
    fun extractSizeCategoryClassifiesCorrectly() {
        assertEquals(ModelMetadataExtractor.SizeCategory.TINY, ModelMetadataExtractor.extractSizeCategory("0.5B"))
        assertEquals(ModelMetadataExtractor.SizeCategory.SMALL, ModelMetadataExtractor.extractSizeCategory("1.5B"))
        assertEquals(ModelMetadataExtractor.SizeCategory.MEDIUM, ModelMetadataExtractor.extractSizeCategory("3B"))
        assertEquals(ModelMetadataExtractor.SizeCategory.LARGE, ModelMetadataExtractor.extractSizeCategory("8B"))
        assertEquals(ModelMetadataExtractor.SizeCategory.XLARGE, ModelMetadataExtractor.extractSizeCategory("70B"))
        assertEquals(ModelMetadataExtractor.SizeCategory.MEDIUM, ModelMetadataExtractor.extractSizeCategory(null))
    }

    @Test
    fun parseSizeToBytesHandlesCommonFormats() {
        assertEquals(1024L, ModelMetadataExtractor.parseSizeToBytes("1 KB"))
        assertEquals(1048576L, ModelMetadataExtractor.parseSizeToBytes("1 MB"))
        assertEquals(524288000L, ModelMetadataExtractor.parseSizeToBytes("500 MB"))
        assertEquals(0L, ModelMetadataExtractor.parseSizeToBytes("invalid"))
    }

    @Test
    fun formatBytesProducesHumanReadable() {
        assertEquals("500 B", ModelMetadataExtractor.formatBytes(500))
        assertEquals("1.0 KB", ModelMetadataExtractor.formatBytes(1024))
        assertEquals("1.0 MB", ModelMetadataExtractor.formatBytes(1024 * 1024))
        assertEquals("1.00 GB", ModelMetadataExtractor.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun isGgufFileDetectsCorrectly() {
        assertTrue(ModelMetadataExtractor.isGgufFile("model.gguf"))
        assertTrue(ModelMetadataExtractor.isGgufFile("Qwen3-8B-Q4_K_M.GGUF"))
        assertFalse(ModelMetadataExtractor.isGgufFile("model.mnn"))
        assertFalse(ModelMetadataExtractor.isGgufFile("model.bin"))
    }

    @Test
    fun isLikelyToolCallingModelDetectsCorrectly() {
        assertTrue(ModelMetadataExtractor.isLikelyToolCallingModel("claude-code-0.5b"))
        assertTrue(ModelMetadataExtractor.isLikelyToolCallingModel("Qwen2.5-Instruct"))
        assertFalse(ModelMetadataExtractor.isLikelyToolCallingModel("gemma-2b"))
    }
}
