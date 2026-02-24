package com.tronprotocol.app.aimodel

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class ModelTrainingManagerTest {

    private lateinit var manager: ModelTrainingManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ModelTrainingManager(context)
    }

    @Test
    fun `initialization does not throw`() {
        // If we reached here, the constructor succeeded without exception
        assertNotNull(manager)
    }

    @Test
    fun `getAllModels returns empty list initially`() {
        val models = manager.getAllModels()
        assertNotNull(models)
        assertTrue(models.isEmpty())
    }

    @Test
    fun `getModel returns null for nonexistent id`() {
        val model = manager.getModel("nonexistent_model_id")
        assertNull(model)
    }

    @Test
    fun `removeModel returns false for nonexistent id`() {
        val removed = manager.removeModel("nonexistent_model_id")
        assertFalse(removed)
    }

    @Test
    fun `getTrainingStats returns valid stats for empty manager`() {
        val stats = manager.getTrainingStats()
        assertNotNull(stats)
        assertEquals(0, stats["total_models"])
        assertEquals(0, stats["total_training_iterations"])
        assertEquals(0.0, stats["avg_accuracy"])
        assertEquals(0, stats["total_knowledge_items"])
        @Suppress("UNCHECKED_CAST")
        val categories = stats["categories"] as List<String>
        assertTrue(categories.isEmpty())
    }

    @Test
    fun `queryModel returns empty list for nonexistent model`() {
        val results = manager.queryModel("nonexistent", "test query")
        assertTrue(results.isEmpty())
    }
}
