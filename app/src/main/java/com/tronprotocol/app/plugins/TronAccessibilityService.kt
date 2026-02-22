package com.tronprotocol.app.plugins

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * System-level AccessibilityService that captures on-screen content.
 * Stores recent screen snapshots for the ScreenReaderPlugin to query.
 */
class TronAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            val entry = JSONObject().apply {
                put("eventType", AccessibilityEvent.eventTypeToString(event.eventType))
                put("packageName", event.packageName?.toString() ?: "")
                put("className", event.className?.toString() ?: "")
                put("text", event.text?.joinToString(" ") ?: "")
                put("timestamp", System.currentTimeMillis())
            }
            recentEvents.addFirst(entry)
            while (recentEvents.size > MAX_EVENTS) {
                recentEvents.removeLast()
            }

            // Capture current window content on state changes
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                captureCurrentScreen()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture accessibility event", e)
        }
    }

    private fun captureCurrentScreen() {
        try {
            val root = rootInActiveWindow ?: return
            val snapshot = JSONObject()
            snapshot.put("timestamp", System.currentTimeMillis())
            snapshot.put("packageName", root.packageName?.toString() ?: "")

            val nodes = JSONArray()
            traverseNode(root, nodes, 0)
            snapshot.put("nodes", nodes)
            snapshot.put("nodeCount", nodes.length())

            currentScreenSnapshot = snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture screen", e)
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, arr: JSONArray, depth: Int) {
        node ?: return
        if (depth > MAX_DEPTH) return

        try {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                arr.put(JSONObject().apply {
                    put("class", node.className?.toString() ?: "")
                    if (text.isNotEmpty()) put("text", text)
                    if (desc.isNotEmpty()) put("description", desc)
                    put("clickable", node.isClickable)
                    put("editable", node.isEditable)
                    put("depth", depth)
                })
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), arr, depth + 1)
            }
        } catch (e: Exception) {
            // Individual node traversal may fail; continue
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceConnected = false
    }

    companion object {
        private const val TAG = "TronAccessibility"
        private const val MAX_EVENTS = 100
        private const val MAX_DEPTH = 15
        val recentEvents = ConcurrentLinkedDeque<JSONObject>()
        @Volatile var currentScreenSnapshot: JSONObject? = null
        @Volatile var serviceConnected: Boolean = false
    }
}
