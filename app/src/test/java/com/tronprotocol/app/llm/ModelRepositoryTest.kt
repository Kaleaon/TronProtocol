package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRepositoryTest {

    @Test
    fun availableModelDiskUsageMbCalculation() {
        val model = ModelRepository.AvailableModel(
            id = "test-model",
            name = "Test Model",
            parameterCount = "1.5B",
            quantization = "Q4",
            family = "Test",
            format = "mnn",
            directory = java.io.File("/tmp/test"),
            diskUsageBytes = 1_073_741_824L, // 1 GB
            contextWindow = 4096,
            catalogEntry = null,
            isFromCatalog = false,
            source = "Test"
        )
        assertEquals("1 GB should be 1024 MB", 1024L, model.diskUsageMb)
    }

    @Test
    fun availableModelZeroDiskUsage() {
        val model = ModelRepository.AvailableModel(
            id = "test-model",
            name = "Test Model",
            parameterCount = "1B",
            quantization = "Q4",
            family = "Test",
            format = "mnn",
            directory = java.io.File("/tmp/test"),
            diskUsageBytes = 0L,
            contextWindow = 2048,
            catalogEntry = null,
            isFromCatalog = false,
            source = "Test"
        )
        assertEquals("0 bytes should be 0 MB", 0L, model.diskUsageMb)
    }

    @Test
    fun modelConfigOverridesDefaultValues() {
        val config = ModelRepository.ModelConfigOverrides()
        assertEquals("Default maxTokens", 512, config.maxTokens)
        assertEquals("Default temperature", 0.7f, config.temperature, 0.001f)
        assertEquals("Default topP", 0.9f, config.topP, 0.001f)
        assertEquals("Default threadCount", 4, config.threadCount)
        assertEquals("Default backend", OnDeviceLLMManager.BACKEND_CPU, config.backend)
        assertEquals("Default useMmap", false, config.useMmap)
    }

    @Test
    fun modelConfigOverridesCustomValues() {
        val config = ModelRepository.ModelConfigOverrides(
            maxTokens = 1024,
            temperature = 0.5f,
            topP = 0.8f,
            threadCount = 8,
            backend = OnDeviceLLMManager.BACKEND_OPENCL,
            useMmap = true
        )
        assertEquals(1024, config.maxTokens)
        assertEquals(0.5f, config.temperature, 0.001f)
        assertEquals(0.8f, config.topP, 0.001f)
        assertEquals(8, config.threadCount)
        assertEquals(OnDeviceLLMManager.BACKEND_OPENCL, config.backend)
        assertTrue(config.useMmap)
    }

    @Test
    fun importedModelEntryDefaults() {
        val entry = ModelRepository.ImportedModelEntry(
            id = "custom-1",
            name = "My Custom Model",
            directory = "/sdcard/models/custom"
        )
        assertEquals("custom-1", entry.id)
        assertEquals("My Custom Model", entry.name)
        assertEquals("/sdcard/models/custom", entry.directory)
        assertEquals("unknown", entry.parameterCount)
        assertEquals("unknown", entry.quantization)
        assertEquals("Custom", entry.family)
    }

    @Test
    fun importedModelEntryWithFullData() {
        val entry = ModelRepository.ImportedModelEntry(
            id = "imported-qwen",
            name = "Qwen from HF",
            directory = "/data/models/qwen",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            family = "Qwen"
        )
        assertEquals("imported-qwen", entry.id)
        assertEquals("1.5B", entry.parameterCount)
        assertEquals("Q4_K_M", entry.quantization)
        assertEquals("Qwen", entry.family)
    }

    @Test
    fun availableModelFromCatalogFlag() {
        val catalogEntry = ModelCatalog.entries.first()
        val model = ModelRepository.AvailableModel(
            id = catalogEntry.id,
            name = catalogEntry.name,
            parameterCount = catalogEntry.parameterCount,
            quantization = catalogEntry.quantization,
            family = catalogEntry.family,
            format = catalogEntry.format,
            directory = java.io.File("/tmp/models/${catalogEntry.id}"),
            diskUsageBytes = catalogEntry.sizeBytes,
            contextWindow = catalogEntry.contextWindow,
            catalogEntry = catalogEntry,
            isFromCatalog = true,
            source = catalogEntry.source
        )
        assertTrue("Should be from catalog", model.isFromCatalog)
        assertNotNull("Should have catalog entry", model.catalogEntry)
        assertEquals(catalogEntry.id, model.catalogEntry!!.id)
    }
}
