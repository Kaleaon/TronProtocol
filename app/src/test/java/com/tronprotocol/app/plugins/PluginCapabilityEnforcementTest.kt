package com.tronprotocol.app.plugins

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class PluginCapabilityEnforcementTest {

    private val pluginManager = PluginManager.getInstance()

    @After
    fun tearDown() {
        pluginManager.destroy()
    }

    @Test
    fun executePlugin_deniesWhenRequiredCapabilityMissing() {
        val context = RuntimeEnvironment.getApplication()
        pluginManager.initialize(context)

        val policyEngine = ToolPolicyEngine().apply { initialize(context) }
        policyEngine.setGrantedCapabilities(CAP_PLUGIN_ID, emptySet())
        pluginManager.attachToolPolicyEngine(policyEngine)

        pluginManager.registerPlugin(CapabilityTestPlugin())
        val result = pluginManager.executePlugin(CAP_PLUGIN_ID, "run")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage?.contains("Missing") == true)
    }

    @Test
    fun executePlugin_allowsWhenRequiredCapabilityPresent() {
        val context = RuntimeEnvironment.getApplication()
        pluginManager.initialize(context)

        val policyEngine = ToolPolicyEngine().apply { initialize(context) }
        policyEngine.setGrantedCapabilities(CAP_PLUGIN_ID, setOf(Capability.NETWORK_OUTBOUND))
        pluginManager.attachToolPolicyEngine(policyEngine)

        pluginManager.registerPlugin(CapabilityTestPlugin())
        val result = pluginManager.executePlugin(CAP_PLUGIN_ID, "run")

        assertTrue(result.isSuccess)
    }

    private class CapabilityTestPlugin : Plugin {
        override val id: String = CAP_PLUGIN_ID
        override val name: String = "Capability Test"
        override val description: String = "test plugin"
        override var isEnabled: Boolean = true

        override fun requiredCapabilities(): Set<Capability> = setOf(Capability.NETWORK_OUTBOUND)

        override fun execute(input: String): PluginResult {
            return PluginResult.success("ok", 1)
        }

        override fun initialize(context: Context) = Unit

        override fun destroy() = Unit
    }

    companion object {
        private const val CAP_PLUGIN_ID = "capability_test_plugin"
    }
}
