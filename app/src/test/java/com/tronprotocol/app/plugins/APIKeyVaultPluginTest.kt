package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class APIKeyVaultPluginTest {

    private lateinit var context: Context
    private lateinit var storage: SecureStorage
    private lateinit var plugin: APIKeyVaultPlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = SecureStorage(context)
        storage.clearAll()

        plugin = APIKeyVaultPlugin()
        plugin.initialize(context)
    }

    @Test
    fun store_persistsServicesAsJsonArray_andPreservesCommasAndWhitespace() {
        val serviceName = "primary, service one"

        val result = plugin.execute("store|$serviceName|token-123")

        assertTrue(result.isSuccess)
        val storedServices = storage.retrieve("vault_services")
        val array = JSONArray(storedServices)
        assertEquals(1, array.length())
        assertEquals(serviceName, array.getString(0))

        val listResult = plugin.execute("list")
        assertTrue(listResult.isSuccess)
        assertTrue(listResult.data?.contains(serviceName) == true)
    }

    @Test
    fun grant_persistsAclAsJsonArray_andPreservesCommasAndWhitespace() {
        val serviceName = "mail"
        val pluginId = "plugin, with spaces"

        plugin.execute("store|$serviceName|token-123")
        val result = plugin.execute("grant|$serviceName|$pluginId")

        assertTrue(result.isSuccess)
        val storedAcl = storage.retrieve("vault_acl_$serviceName")
        val array = JSONArray(storedAcl)
        assertEquals(1, array.length())
        assertEquals(pluginId, array.getString(0))

        val checkResult = plugin.execute("check|$serviceName|$pluginId")
        assertTrue(checkResult.isSuccess)
        assertTrue(checkResult.data?.contains("true") == true)
    }

    @Test
    fun list_migratesLegacyCommaSeparatedServicesToJsonArray() {
        storage.store("vault_services", "alpha,beta")

        val result = plugin.execute("list")

        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("alpha") == true)
        assertTrue(result.data?.contains("beta") == true)

        val migrated = storage.retrieve("vault_services")
        val array = JSONArray(migrated)
        assertEquals(2, array.length())
        assertEquals("alpha", array.getString(0))
        assertEquals("beta", array.getString(1))
    }
}
