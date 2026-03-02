package com.tronprotocol.app.llm.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {

    @Test
    fun detectFormatRecognizesGgufExtension() {
        val selector = BackendSelector()
        assertEquals(BackendType.GGUF, selector.detectFormat("/path/to/model.gguf"))
        assertEquals(BackendType.GGUF, selector.detectFormat("/path/to/model.GGUF"))
    }

    @Test
    fun detectFormatReturnsNullForUnknown() {
        val selector = BackendSelector()
        assertNull(selector.detectFormat("/path/to/model.bin"))
        assertNull(selector.detectFormat("/nonexistent/path"))
    }

    @Test
    fun backendTypeFromStringParsesCorrectly() {
        assertEquals(BackendType.MNN, BackendType.fromString("mnn"))
        assertEquals(BackendType.GGUF, BackendType.fromString("gguf"))
        assertEquals(BackendType.GGUF, BackendType.fromString("llama"))
        assertEquals(BackendType.GGUF, BackendType.fromString("llama.cpp"))
        assertNull(BackendType.fromString("unknown"))
    }

    @Test
    fun backendTypeHasCorrectExtensions() {
        assertEquals(".mnn", BackendType.MNN.fileExtension)
        assertEquals(".gguf", BackendType.GGUF.fileExtension)
    }

    @Test
    fun getAllBackendsReturnsTwo() {
        val selector = BackendSelector()
        assertEquals(2, selector.getAllBackends().size)
    }

    @Test
    fun getBackendByTypeReturnsCorrectBackend() {
        val selector = BackendSelector()
        assertEquals("mnn", selector.getBackend(BackendType.MNN).name)
        assertEquals("gguf", selector.getBackend(BackendType.GGUF).name)
    }
}
