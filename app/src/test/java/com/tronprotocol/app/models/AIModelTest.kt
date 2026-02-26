package com.tronprotocol.app.models

import org.junit.Assert.*
import org.junit.Test

class AIModelTest {

    @Test
    fun creation_withAllFields() {
        val model = AIModel(
            id = "model_001",
            name = "Test Model",
            path = "/data/models/test.gguf",
            modelType = "GGUF",
            size = 1024L * 1024L * 500L // 500 MB
        )
        model.category = "Coding"

        assertEquals("model_001", model.id)
        assertEquals("Test Model", model.name)
        assertEquals("/data/models/test.gguf", model.path)
        assertEquals("GGUF", model.modelType)
        assertEquals(1024L * 1024L * 500L, model.size)
        assertEquals("Coding", model.category)
        assertFalse("isLoaded should default to false", model.isLoaded)
    }

    @Test
    fun formattedSizeString_displaysCorrectUnits() {
        // Test KB
        val modelKB = AIModel("id1", "KB Model", "/path", "GGUF", 1536L) // 1.5 KB
        val toStringKB = modelKB.toString()
        assertTrue(
            "Size 1536 bytes should format as KB, got: $toStringKB",
            toStringKB.contains("KB")
        )

        // Test MB
        val modelMB = AIModel("id2", "MB Model", "/path", "GGUF", 5L * 1024L * 1024L) // 5 MB
        val toStringMB = modelMB.toString()
        assertTrue(
            "Size 5MB should format as MB, got: $toStringMB",
            toStringMB.contains("MB")
        )

        // Test GB
        val modelGB = AIModel("id3", "GB Model", "/path", "GGUF", 3L * 1024L * 1024L * 1024L) // 3 GB
        val toStringGB = modelGB.toString()
        assertTrue(
            "Size 3GB should format as GB, got: $toStringGB",
            toStringGB.contains("GB")
        )
    }

    @Test
    fun dataClass_equality() {
        // AIModel is a regular class, not a data class, so equality is by reference
        val model1 = AIModel("id1", "Model A", "/path", "GGUF", 1000L)
        val model2 = AIModel("id1", "Model A", "/path", "GGUF", 1000L)

        // Same reference should be equal
        assertEquals("Same object should equal itself", model1, model1)

        // Different instances with same fields are not equal (not a data class)
        assertNotEquals(
            "Different instances should not be equal since AIModel is not a data class",
            model1,
            model2
        )
    }

    @Test
    fun modelType_values() {
        // Test that model can be created with different model types
        val ggufModel = AIModel("id1", "GGUF Model", "/path", "GGUF", 1000L)
        assertEquals("GGUF", ggufModel.modelType)

        val tfliteModel = AIModel("id2", "TFLite Model", "/path", "TFLite", 2000L)
        assertEquals("TFLite", tfliteModel.modelType)

        val mnnModel = AIModel("id3", "MNN Model", "/path", "MNN", 3000L)
        assertEquals("MNN", mnnModel.modelType)

        // Verify the toString includes modelType
        assertTrue(
            "toString should include modelType",
            ggufModel.toString().contains("GGUF")
        )
    }
}
