package com.tronprotocol.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class TronProtocolServiceTest {

    @Test
    fun `service can be created`() {
        val controller: ServiceController<TronProtocolService> =
            Robolectric.buildService(TronProtocolService::class.java)
        val service = controller.create().get()
        assertNotNull(service)
        controller.destroy()
    }

    @Test
    fun `onStartCommand returns START_STICKY`() {
        val controller = Robolectric.buildService(TronProtocolService::class.java)
        val service = controller.create().get()
        val result = service.onStartCommand(Intent(), 0, 1)
        assertEquals(Service.START_STICKY, result)
        controller.destroy()
    }

    @Test
    fun `service creates notification channel on API 26+`() {
        // Robolectric runs with the target SDK (API 34), which is >= 26
        val controller = Robolectric.buildService(TronProtocolService::class.java)
        val service = controller.create().get()

        val notificationManager =
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel("TronProtocolServiceChannel")

        assertNotNull("Notification channel should be created on API 26+", channel)
        assertEquals("Tron Protocol Service", channel.name.toString())
        controller.destroy()
    }

    @Test
    fun `onDestroy cleans up without error`() {
        val controller = Robolectric.buildService(TronProtocolService::class.java)
        controller.create()
        // onStartCommand to set up threads/executors that onDestroy will tear down
        controller.get().onStartCommand(Intent(), 0, 1)
        // Should not throw any exception
        controller.destroy()
    }
}
