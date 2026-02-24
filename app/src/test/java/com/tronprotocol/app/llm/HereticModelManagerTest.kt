package com.tronprotocol.app.llm

import android.content.Context
import com.tronprotocol.app.guidance.ConstitutionalValuesEngine
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ConstitutionalMemory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class HereticModelManagerTest {

    private lateinit var manager: HereticModelManager
    private lateinit var onDeviceLLMManager: OnDeviceLLMManager
    private lateinit var valuesEngine: ConstitutionalValuesEngine
    private lateinit var constitutionalMemory: ConstitutionalMemory
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        onDeviceLLMManager = mock(OnDeviceLLMManager::class.java)
        constitutionalMemory = ConstitutionalMemory(context)
        valuesEngine = ConstitutionalValuesEngine(constitutionalMemory)
        manager = HereticModelManager(context, onDeviceLLMManager, valuesEngine)
    }

    @Test
    fun `initial state is not ready`() {
        `when`(onDeviceLLMManager.isReady).thenReturn(false)
        assertFalse(manager.isReady)
    }

    @Test
    fun `isReady delegates to OnDeviceLLMManager`() {
        `when`(onDeviceLLMManager.isReady).thenReturn(true)
        assertTrue(manager.isReady)

        `when`(onDeviceLLMManager.isReady).thenReturn(false)
        assertFalse(manager.isReady)
    }

    @Test
    fun `generate blocks harmful prompt`() {
        // "rm -rf" is in the constitutional memory's safety directives
        val result = manager.generate("please run rm -rf /")
        assertFalse(result.success)
        assertFalse(result.valuesAllowed)
        assertTrue(result.valuesViolations.isNotEmpty())
        assertNotNull(result.error)
    }

    @Test
    fun `generate allows safe prompt when model is not loaded`() {
        `when`(onDeviceLLMManager.isReady).thenReturn(false)
        val result = manager.generate("What is the weather today?")
        // Prompt passes values gate but model is not loaded
        assertFalse(result.success)
        assertTrue(result.valuesAllowed)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("No heretic model loaded"))
    }

    @Test
    fun `activeConfig delegates to OnDeviceLLMManager`() {
        `when`(onDeviceLLMManager.activeConfig).thenReturn(null)
        assertNull(manager.activeConfig)
    }

    @Test
    fun `registerHereticModel adds model to registry`() {
        val metadata = HereticModelManager.HereticModelMetadata(
            sourceModelId = "qwen-1.5b",
            sourceModelName = "Qwen2.5-1.5B-Instruct",
            hereticVersion = "0.1.0",
            ablationMethod = HereticModelManager.AblationMethod.DIRECTIONAL,
            directionIndex = 0.8f,
            maxWeight = 1.0f,
            klDivergence = 0.02f,
            refusalRate = 0.01f,
            processedTimestamp = System.currentTimeMillis()
        )
        manager.registerHereticModel("test-model-1", metadata)
        assertTrue(manager.isHereticModel("test-model-1"))
        assertFalse(manager.isHereticModel("nonexistent-model"))
    }

    @Test
    fun `getModelMetadata returns registered metadata`() {
        val metadata = HereticModelManager.HereticModelMetadata(
            sourceModelId = "gemma-2b",
            sourceModelName = "Gemma-2B",
            hereticVersion = "0.2.0",
            ablationMethod = HereticModelManager.AblationMethod.PARAMETRIZED_KERNEL,
            directionIndex = 0.9f,
            maxWeight = 1.2f,
            klDivergence = null,
            refusalRate = null,
            processedTimestamp = System.currentTimeMillis()
        )
        manager.registerHereticModel("gemma-test", metadata)

        val retrieved = manager.getModelMetadata("gemma-test")
        assertNotNull(retrieved)
        assertEquals("gemma-2b", retrieved!!.sourceModelId)
        assertEquals(HereticModelManager.AblationMethod.PARAMETRIZED_KERNEL, retrieved.ablationMethod)
    }

    @Test
    fun `getModelMetadata returns null for unregistered model`() {
        assertNull(manager.getModelMetadata("not-registered"))
    }

    @Test
    fun `getStats returns statistics map`() {
        `when`(onDeviceLLMManager.getStats()).thenReturn(mapOf("model_loaded" to false))

        val stats = manager.getStats()
        assertNotNull(stats)
        assertTrue(stats.containsKey("heretic_total_generations"))
        assertTrue(stats.containsKey("heretic_prompts_blocked"))
        assertTrue(stats.containsKey("heretic_responses_blocked"))
        assertTrue(stats.containsKey("heretic_successful_generations"))
        assertTrue(stats.containsKey("heretic_registered_models"))
        assertEquals(0L, stats["heretic_total_generations"])
    }

    @Test
    fun `getRegisteredModels returns empty map initially`() {
        val models = manager.getRegisteredModels()
        assertTrue(models.isEmpty())
    }

    @Test
    fun `shutdown does not throw`() {
        manager.shutdown()
    }

    @Test
    fun `generate increments total generation count`() {
        `when`(onDeviceLLMManager.isReady).thenReturn(false)
        `when`(onDeviceLLMManager.getStats()).thenReturn(emptyMap())

        // Generate a safe prompt (will fail because model not loaded, but still counts)
        manager.generate("Hello world")

        val stats = manager.getStats()
        assertEquals(1L, stats["heretic_total_generations"])
    }

    @Test
    fun `generate with harmful prompt increments prompts blocked count`() {
        `when`(onDeviceLLMManager.getStats()).thenReturn(emptyMap())

        manager.generate("drop table users")

        val stats = manager.getStats()
        assertEquals(1L, stats["heretic_prompts_blocked"])
    }

    @Test
    fun `ablation method enum has all expected values`() {
        val methods = HereticModelManager.AblationMethod.values()
        assertEquals(4, methods.size)
        assertTrue(methods.contains(HereticModelManager.AblationMethod.DIRECTIONAL))
        assertTrue(methods.contains(HereticModelManager.AblationMethod.PROJECTED))
        assertTrue(methods.contains(HereticModelManager.AblationMethod.NORM_PRESERVING))
        assertTrue(methods.contains(HereticModelManager.AblationMethod.PARAMETRIZED_KERNEL))
    }
}
