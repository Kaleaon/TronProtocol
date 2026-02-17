package com.tronprotocol.app.plugins.security

import android.util.Log
import com.tronprotocol.app.plugins.CalculatorPlugin
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.ToolPolicyEngine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

class PolicyDenialObservabilityTest {

    private lateinit var engine: ToolPolicyEngine

    @Before
    fun setUp() {
        ShadowLog.setupLogging()
        engine = ToolPolicyEngine().also { it.initialize(RuntimeEnvironment.getApplication()) }
    }

    @After
    fun tearDown() {
        PluginManager.getInstance().destroy()
    }

    @Test
    fun policyDenialIsLoggedWithStructuredMessage() {
        engine.addRule(
            ToolPolicyEngine.PolicyRule(
                layer = ToolPolicyEngine.PolicyLayer.PLUGIN_PROFILE,
                pluginId = "calculator",
                action = ToolPolicyEngine.Action.DENY,
                reason = "security regression gate"
            )
        )

        engine.evaluate("calculator")

        val denialLog = ShadowLog.getLogsForTag("ToolPolicyEngine")
            .any { it.type == Log.WARN && it.msg.contains("POLICY_DENIED plugin=calculator") }
        assertTrue(denialLog)
    }

    @Test
    fun pluginManagerSurfacesPolicyDenialReasonPredictably() {
        val manager = PluginManager.getInstance()
        manager.initialize(RuntimeEnvironment.getApplication())
        manager.attachToolPolicyEngine(engine)
        manager.registerPlugin(CalculatorPlugin())

        engine.addRule(
            ToolPolicyEngine.PolicyRule(
                layer = ToolPolicyEngine.PolicyLayer.PLUGIN_PROFILE,
                pluginId = "calculator",
                action = ToolPolicyEngine.Action.DENY,
                reason = "blocked by test policy"
            )
        )

        val result = manager.executePlugin("calculator", "add|1|1")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Denied by PLUGIN_PROFILE policy: blocked by test policy"))
    }
}
