package com.tronprotocol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class StartupDiagnosticsTest {

    @Test
    fun exportDebugLogIncludesRuntimeSection() {
        val context = RuntimeEnvironment.getApplication()
        StartupDiagnostics.setDebugSection("llm_manager", "model_state=READY")

        val file = StartupDiagnostics.exportDebugLog(context)
        val contents = file.readText()

        assertTrue(contents.contains("[llm_manager]"))
        assertTrue(contents.contains("model_state=READY"))
    }
}
