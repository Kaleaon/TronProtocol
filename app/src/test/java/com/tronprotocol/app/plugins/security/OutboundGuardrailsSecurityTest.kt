package com.tronprotocol.app.plugins.security

import com.tronprotocol.app.plugins.CommunicationHubPlugin
import com.tronprotocol.app.plugins.WebSearchPlugin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class OutboundGuardrailsSecurityTest {

    private lateinit var communicationHubPlugin: CommunicationHubPlugin
    private lateinit var webSearchPlugin: WebSearchPlugin

    @Before
    fun setUp() {
        communicationHubPlugin = CommunicationHubPlugin().also {
            it.initialize(RuntimeEnvironment.getApplication())
        }
        webSearchPlugin = WebSearchPlugin().also {
            it.initialize(RuntimeEnvironment.getApplication())
        }
    }

    @Test
    fun communicationHubBlocksRestrictedHosts() {
        communicationHubPlugin.execute("add_channel|danger|https://127.0.0.1/webhook")

        val result = communicationHubPlugin.execute("send|danger|${SecurityMaliciousPayloadFixtures.COMMAND_LIKE_STRING}")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Outbound request blocked: restricted host"))
    }

    @Test
    fun communicationHubRequiresHttpsEndpoints() {
        communicationHubPlugin.execute("add_channel|danger|http://example.com/webhook")

        val result = communicationHubPlugin.execute("send|danger|ok")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Outbound request blocked: HTTPS is required"))
    }

    @Test
    fun webSearchHostBlocklistFlagsLocalHosts() {
        assertTrue(webSearchPlugin.isHostBlocked("localhost"))
        assertTrue(webSearchPlugin.isHostBlocked("192.168.1.9"))
        assertFalse(webSearchPlugin.isHostBlocked("html.duckduckgo.com"))
    }

    @Test
    fun webSearchRejectsOversizedInputPredictably() {
        val result = webSearchPlugin.execute(SecurityMaliciousPayloadFixtures.OVERSIZED_INPUT)

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Search query too large"))
    }
}
