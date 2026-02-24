package com.tronprotocol.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelegramBridgePluginTest {

    private lateinit var context: Context
    private lateinit var plugin: TelegramBridgePlugin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("telegram_bridge_plugin", Context.MODE_PRIVATE).edit().clear().commit()
        plugin = TelegramBridgePlugin()
        plugin.initialize(context)
    }

    @Test
    fun importShared_savesBotTokenFromText() {
        val result = plugin.execute("import_shared|Token: 123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789")

        assertTrue(result.isSuccess)
        val savedToken = context.getSharedPreferences("telegram_bridge_plugin", Context.MODE_PRIVATE)
            .getString("bot_token", "")
        assertTrue(savedToken?.startsWith("123456789:") == true)
    }

    @Test
    fun importShared_allowsChatFromChatIdParameter() {
        val result = plugin.execute("import_shared|chat_id=-1001234567890")

        assertTrue(result.isSuccess)
        val allowed = plugin.execute("list_allowed")
        assertTrue(allowed.isSuccess)
        assertTrue(allowed.data?.contains("-1001234567890") == true)
    }

    @Test
    fun importShared_allowsChatFromTelegramPrivateLink() {
        val result = plugin.execute("import_shared|https://t.me/c/1234567890/27")

        assertTrue(result.isSuccess)
        val allowed = plugin.execute("list_allowed")
        assertTrue(allowed.data?.contains("-1001234567890") == true)
    }

    @Test
    fun importShared_withoutTelegramData_returnsError() {
        val result = plugin.execute("import_shared|hello there")

        assertFalse(result.isSuccess)
    }
}
