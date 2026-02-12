package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class OnDeviceLLMManagerTest {

    @Test
    fun assessDeviceDoesNotOverrideModelState() {
        val manager = OnDeviceLLMManager(RuntimeEnvironment.getApplication())
        val initialState = manager.modelState

        manager.assessDevice()

        assertEquals(initialState, manager.modelState)
    }
}
