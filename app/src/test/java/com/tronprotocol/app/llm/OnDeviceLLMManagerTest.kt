package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OnDeviceLLMManagerTest {

    @Test
    fun assessDeviceDoesNotOverrideModelState() {
        val manager = OnDeviceLLMManager(RuntimeEnvironment.getApplication())
        val initialState = manager.modelState

        manager.assessDevice()

        assertEquals(initialState, manager.modelState)
    }

    @Test
    fun createConfigFromDirectoryMarksLegacyModelsUntrusted() {
        val modelDir = File(System.getProperty("java.io.tmpdir"), "legacy-${UUID.randomUUID()}")
        modelDir.mkdirs()
        File(modelDir, "config.json").writeText("{\"backend_type\":\"cpu\",\"thread_num\":2}")

        val manager = OnDeviceLLMManager(RuntimeEnvironment.getApplication())
        val config = manager.createConfigFromDirectory(modelDir)

        assertEquals(LLMModelConfig.IntegrityStatus.UNTRUSTED_MIGRATED, config.integrityStatus)
        assertFalse(config.artifacts.isNotEmpty())
    }
}
