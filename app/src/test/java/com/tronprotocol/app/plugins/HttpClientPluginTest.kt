package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpClientPluginTest {

    private lateinit var context: Context
    private lateinit var plugin: HttpClientPlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        plugin = HttpClientPlugin()
        plugin.initialize(context)
    }

    @Test
    fun getWithHttpUrl_returnsSecurityError() {
        val result = plugin.execute("get|http://example.com/api")
        assertFalse("GET with HTTP (not HTTPS) should fail", result.isSuccess)
        assertTrue(
            "Error should mention HTTPS required",
            result.errorMessage?.contains("HTTPS") == true ||
                    result.errorMessage?.contains("security") == true
        )
    }

    @Test
    fun getWithLocalhost_returnsSecurityError() {
        val result = plugin.execute("get|https://localhost/api")
        assertFalse("GET with localhost should fail", result.isSuccess)
        assertTrue(
            "Error should mention local/private addresses blocked",
            result.errorMessage?.contains("Local") == true ||
                    result.errorMessage?.contains("blocked") == true ||
                    result.errorMessage?.contains("security") == true
        )
    }

    @Test
    fun getWithPrivateIP_returnsSecurityError() {
        val result = plugin.execute("get|https://192.168.1.1/api")
        assertFalse("GET with private IP should fail", result.isSuccess)
        assertTrue(
            "Error should mention local/private addresses blocked",
            result.errorMessage?.contains("Local") == true ||
                    result.errorMessage?.contains("blocked") == true ||
                    result.errorMessage?.contains("security") == true
        )
    }

    @Test
    fun allowDomain_addsDomain() {
        val result = plugin.execute("allow_domain|api.example.com")
        assertTrue("allow_domain should succeed", result.isSuccess)
        assertTrue(
            "Result should confirm domain was allowed",
            result.data?.contains("api.example.com") == true ||
                    result.data?.contains("allowed") == true
        )
    }

    @Test
    fun listDomains_showsAllowedDomains() {
        plugin.execute("allow_domain|api.test.com")
        plugin.execute("allow_domain|api.other.com")

        val result = plugin.execute("list_domains")
        assertTrue("list_domains should succeed", result.isSuccess)
        assertTrue(
            "Result should contain allowed domains",
            result.data?.contains("api.test.com") == true &&
                    result.data?.contains("api.other.com") == true
        )
    }

    @Test
    fun setHeader_andListHeaders_work() {
        val setResult = plugin.execute("set_header|Authorization|Bearer token123")
        assertTrue("set_header should succeed", setResult.isSuccess)
        assertTrue(
            "Set result should confirm header was set",
            setResult.data?.contains("Authorization") == true
        )

        val listResult = plugin.execute("list_headers")
        assertTrue("list_headers should succeed", listResult.isSuccess)
        assertTrue(
            "List should include the set header",
            listResult.data?.contains("Authorization") == true
        )
    }

    @Test
    fun pluginId_isHttpClient() {
        assertEquals("Plugin ID should be 'http_client'", "http_client", plugin.id)
    }
}
