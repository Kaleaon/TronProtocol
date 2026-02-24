package com.tronprotocol.app.aimodel

import org.junit.Assert.*
import org.junit.Test

class TrainedModelTest {

    @Test
    fun `creation with all fields`() {
        val model = TrainedModel(
            id = "model_001",
            name = "TestModel",
            category = "general",
            conceptCount = 10,
            knowledgeSize = 500,
            createdTimestamp = 1700000000000L
        )

        assertEquals("model_001", model.id)
        assertEquals("TestModel", model.name)
        assertEquals("general", model.category)
        assertEquals(10, model.conceptCount)
        assertEquals(500, model.knowledgeSize)
        assertEquals(1700000000000L, model.createdTimestamp)
    }

    @Test
    fun `default accuracy is zero`() {
        val model = createTestModel()
        assertEquals(0.0, model.accuracy, 0.001)
    }

    @Test
    fun `accuracy can be set between 0 and 1`() {
        val model = createTestModel()
        model.accuracy = 0.85
        assertEquals(0.85, model.accuracy, 0.001)
        assertTrue(model.accuracy >= 0.0)
        assertTrue(model.accuracy <= 1.0)
    }

    @Test
    fun `default training iterations is zero`() {
        val model = createTestModel()
        assertEquals(0, model.trainingIterations)
    }

    @Test
    fun `training iterations can be incremented`() {
        val model = createTestModel()
        model.trainingIterations = 5
        assertEquals(5, model.trainingIterations)
    }

    @Test
    fun `lastTrainedTimestamp defaults to createdTimestamp`() {
        val timestamp = 1700000000000L
        val model = TrainedModel(
            id = "model_001",
            name = "Test",
            category = "test",
            conceptCount = 0,
            knowledgeSize = 0,
            createdTimestamp = timestamp
        )
        assertEquals(timestamp, model.lastTrainedTimestamp)
    }

    @Test
    fun `knowledgeBase starts empty`() {
        val model = createTestModel()
        assertTrue(model.knowledgeBase.isEmpty())
    }

    @Test
    fun `knowledgeBase can be populated`() {
        val model = createTestModel()
        model.knowledgeBase.add("Knowledge item 1")
        model.knowledgeBase.add("Knowledge item 2")
        assertEquals(2, model.knowledgeBase.size)
        assertEquals("Knowledge item 1", model.knowledgeBase[0])
    }

    @Test
    fun `embeddings starts empty`() {
        val model = createTestModel()
        assertTrue(model.embeddings.isEmpty())
    }

    @Test
    fun `embeddings can be populated`() {
        val model = createTestModel()
        model.embeddings.add(floatArrayOf(1.0f, 2.0f, 3.0f))
        assertEquals(1, model.embeddings.size)
        assertArrayEquals(floatArrayOf(1.0f, 2.0f, 3.0f), model.embeddings[0], 0.001f)
    }

    @Test
    fun `parameters starts empty`() {
        val model = createTestModel()
        assertTrue(model.parameters.isEmpty())
    }

    @Test
    fun `parameters can be populated`() {
        val model = createTestModel()
        model.parameters["coverage"] = 0.75f
        model.parameters["diversity"] = 0.6f
        assertEquals(2, model.parameters.size)
        assertEquals(0.75f, model.parameters["coverage"])
    }

    @Test
    fun `two models with same constructor values are distinct objects`() {
        val model1 = TrainedModel("id", "name", "cat", 5, 100, 1L)
        val model2 = TrainedModel("id", "name", "cat", 5, 100, 1L)
        // TrainedModel is a class (not data class), so equality is reference-based
        assertNotSame(model1, model2)
    }

    @Test
    fun `model with modified accuracy reflects change`() {
        val model = createTestModel()
        model.accuracy = 0.5
        assertEquals(0.5, model.accuracy, 0.001)

        model.accuracy = 0.95
        assertEquals(0.95, model.accuracy, 0.001)
    }

    @Test
    fun `toString contains model name and accuracy`() {
        val model = createTestModel()
        model.accuracy = 0.85
        val str = model.toString()
        assertTrue(str.contains("TestModel"))
        assertTrue(str.contains("85.00%"))
    }

    @Test
    fun `toString contains model id`() {
        val model = createTestModel()
        val str = model.toString()
        assertTrue(str.contains("model_test"))
    }

    @Test
    fun `toString contains category`() {
        val model = createTestModel()
        val str = model.toString()
        assertTrue(str.contains("general"))
    }

    private fun createTestModel(): TrainedModel {
        return TrainedModel(
            id = "model_test",
            name = "TestModel",
            category = "general",
            conceptCount = 5,
            knowledgeSize = 200,
            createdTimestamp = System.currentTimeMillis()
        )
    }
}
