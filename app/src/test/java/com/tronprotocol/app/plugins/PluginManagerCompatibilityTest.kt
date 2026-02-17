package com.tronprotocol.app.plugins

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerCompatibilityTest {

    @Test
    fun legacyStringEntrypoint_isConvertedToTypedRequest() {
        val manager = PluginManager.getInstance()
        val plugin = CapturingPlugin()
        injectPlugin(manager, plugin)

        val result = manager.executePlugin("capturing", "ping|payload")

        assertTrue(result.isSuccess)
        assertEquals("ping", plugin.lastRequest?.command)
        assertEquals("payload", plugin.lastRequest?.args?.get("arg0"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectPlugin(manager: PluginManager, plugin: Plugin) {
        val field = PluginManager::class.java.getDeclaredField("plugins")
        field.isAccessible = true
        val map = field.get(manager) as MutableMap<String, Plugin>
        map[plugin.id] = plugin
    }

    private class CapturingPlugin : Plugin {
        override val id: String = "capturing"
        override val name: String = "capturing"
        override val description: String = "captures request"
        override var isEnabled: Boolean = true
        var lastRequest: PluginRequest? = null

        override fun execute(request: PluginRequest): PluginResponse {
            lastRequest = request
            return PluginResponse.success("ok", 1)
        }

        override fun execute(input: String): PluginResult {
            return execute(PluginRequest.fromLegacyInput(input)).toPluginResult()
        }

        override fun initialize(context: Context) = Unit
        override fun destroy() = Unit
    }
}

