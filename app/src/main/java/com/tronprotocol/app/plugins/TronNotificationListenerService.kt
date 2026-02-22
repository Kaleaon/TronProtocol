package com.tronprotocol.app.plugins

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * System-level NotificationListenerService that captures device notifications.
 * Stores recent notifications in a bounded buffer for the NotificationListenerPlugin to query.
 */
class TronNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val entry = JSONObject().apply {
                put("package", sbn.packageName)
                put("id", sbn.id)
                put("postTime", sbn.postTime)
                put("title", sbn.notification.extras.getCharSequence("android.title")?.toString() ?: "")
                put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
                put("subText", sbn.notification.extras.getCharSequence("android.subText")?.toString() ?: "")
                put("category", sbn.notification.category ?: "")
                put("isClearable", sbn.isClearable)
                put("isOngoing", sbn.isOngoing)
            }
            recentNotifications.addFirst(entry)
            while (recentNotifications.size > MAX_BUFFER) {
                recentNotifications.removeLast()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: track removals
    }

    companion object {
        private const val TAG = "TronNotifListener"
        private const val MAX_BUFFER = 200
        val recentNotifications = ConcurrentLinkedDeque<JSONObject>()
    }
}
