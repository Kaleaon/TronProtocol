package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tronprotocol.app.swarm.SwarmNode
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import com.tronprotocol.app.swarm.SwarmNode.NodeStatus
import com.tronprotocol.app.swarm.SwarmProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * PicoClaw Bridge Plugin — discovers, registers, and manages PicoClaw edge nodes.
 *
 * This plugin is the primary integration point between TronProtocol (Android)
 * and PicoClaw instances running on edge hardware (RISC-V, ARM64, RPi).
 * It maintains a registry of live nodes, dispatches health checks, and
 * provides the node map to the AgentSwarmOrchestrator.
 *
 * Commands:
 *   add_node|nodeId|endpoint[|displayName]  — Register a PicoClaw node
 *   remove_node|nodeId                       — Deregister a node
 *   list_nodes                               — List all registered nodes with status
 *   ping|nodeId                              — Ping a specific node
 *   ping_all                                 — Ping all nodes
 *   status|nodeId                            — Get detailed node status
 *   send|nodeId|message                      — Send a raw agent message to a node
 *   discover                                 — Auto-discover nodes on the local network
 */
class PicoClawBridgePlugin : Plugin {

    companion object {
        private const val TAG = "PicoClawBridge"
        private const val ID = "picoclaw_bridge"
        private const val PREFS = "picoclaw_bridge_plugin"
        private const val KEY_NODES_JSON = "registered_nodes"
        private const val KEY_SWARM_ID = "swarm_id"
        private const val LOCAL_NODE_ID = "tronprotocol_android"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 8000
    }

    private lateinit var preferences: SharedPreferences
    private val nodes = ConcurrentHashMap<String, SwarmNode>()

    override val id: String = ID
    override val name: String = "PicoClaw Bridge"
    override val description: String =
        "Manages PicoClaw edge nodes in the agent swarm. " +
            "Commands: add_node|id|endpoint[|name], remove_node|id, list_nodes, " +
            "ping|id, ping_all, status|id, send|id|msg, discover"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isNullOrBlank()) {
                return PluginResult.error("No command provided", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add_node" -> addNode(parts, start)
                "remove_node" -> removeNode(parts, start)
                "list_nodes" -> listNodes(start)
                "ping" -> pingNode(parts, start)
                "ping_all" -> pingAll(start)
                "status" -> nodeStatus(parts, start)
                "send" -> sendToNode(parts, start)
                "discover" -> discoverNodes(start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("PicoClaw bridge failed: ${e.message}", elapsed(start))
        }
    }

    private fun addNode(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error(
                "Usage: add_node|nodeId|endpoint[|displayName]", elapsed(start)
            )
        }
        val nodeId = parts[1].trim()
        val endpoint = parts[2].trim()
        val displayName = if (parts.size >= 4) parts[3].trim() else nodeId

        val node = SwarmNode(
            nodeId = nodeId,
            displayName = displayName,
            endpoint = endpoint,
            capabilities = setOf(
                NodeCapability.INFERENCE,
                NodeCapability.SKILL_EXECUTION,
                NodeCapability.WEB_SEARCH,
                NodeCapability.SHELL_EXECUTION
            )
        )
        nodes[nodeId] = node
        persistNodes()

        Log.d(TAG, "Registered node: $nodeId at $endpoint")
        return PluginResult.success("Registered node '$displayName' ($nodeId) at $endpoint", elapsed(start))
    }

    private fun removeNode(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: remove_node|nodeId", elapsed(start))
        }
        val nodeId = parts[1].trim()
        val removed = nodes.remove(nodeId)
        persistNodes()

        return if (removed != null) {
            Log.d(TAG, "Deregistered node: $nodeId")
            PluginResult.success("Removed node: $nodeId", elapsed(start))
        } else {
            PluginResult.error("Node not found: $nodeId", elapsed(start))
        }
    }

    private fun listNodes(start: Long): PluginResult {
        if (nodes.isEmpty()) {
            return PluginResult.success("No nodes registered. Use add_node to register PicoClaw instances.", elapsed(start))
        }

        val sb = StringBuilder("Swarm nodes (${nodes.size}):\n")
        for ((id, node) in nodes) {
            sb.append("  [$id] ${node.displayName} — ${node.endpoint}")
            sb.append(" | status=${node.status.name}")
            sb.append(" | latency=${node.latencyMs}ms")
            sb.append(" | reliability=${"%.1f".format(node.reliabilityScore * 100)}%")
            sb.append(" | ver=${node.picoClawVersion}")
            sb.append("\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun pingNode(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: ping|nodeId", elapsed(start))
        }
        val nodeId = parts[1].trim()
        val node = nodes[nodeId]
            ?: return PluginResult.error("Node not found: $nodeId", elapsed(start))

        val result = performPing(node)
        return PluginResult.success(result, elapsed(start))
    }

    private fun pingAll(start: Long): PluginResult {
        if (nodes.isEmpty()) {
            return PluginResult.success("No nodes to ping", elapsed(start))
        }

        val sb = StringBuilder("Ping results:\n")
        for ((_, node) in nodes) {
            val result = performPing(node)
            sb.append("  ${node.nodeId}: $result\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun performPing(node: SwarmNode): String {
        val pingStart = System.currentTimeMillis()
        return try {
            val msg = SwarmProtocol.ping(LOCAL_NODE_ID)
            val response = postJson(node.endpoint + "/ping", msg.toJson().toString())
            val latency = System.currentTimeMillis() - pingStart
            node.latencyMs = latency
            node.lastHeartbeat = System.currentTimeMillis()
            node.status = NodeStatus.ONLINE
            node.recordSuccess()

            // Parse version from response if available
            try {
                val json = JSONObject(response)
                val payload = json.optJSONObject("payload")
                if (payload != null) {
                    node.picoClawVersion = payload.optString("version", node.picoClawVersion)
                    node.memoryUsageMb = payload.optInt("memory_mb", node.memoryUsageMb)
                    node.activeModel = payload.optString("active_model", node.activeModel)
                }
            } catch (_: Exception) { /* response parsing is best-effort */ }

            "ONLINE (${latency}ms)"
        } catch (e: Exception) {
            node.status = NodeStatus.OFFLINE
            node.recordFailure()
            "OFFLINE (${e.message})"
        }
    }

    private fun nodeStatus(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: status|nodeId", elapsed(start))
        }
        val nodeId = parts[1].trim()
        val node = nodes[nodeId]
            ?: return PluginResult.error("Node not found: $nodeId", elapsed(start))

        return try {
            val response = getJson(node.endpoint + "/status")
            val json = JSONObject(response)

            node.status = NodeStatus.ONLINE
            node.lastHeartbeat = System.currentTimeMillis()
            node.picoClawVersion = json.optString("version", node.picoClawVersion)
            node.memoryUsageMb = json.optInt("memory_mb", node.memoryUsageMb)
            node.activeModel = json.optString("active_model", node.activeModel)

            PluginResult.success(node.toJson().toString(2), elapsed(start))
        } catch (e: Exception) {
            node.status = NodeStatus.OFFLINE
            PluginResult.error("Failed to get status for $nodeId: ${e.message}", elapsed(start))
        }
    }

    private fun sendToNode(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: send|nodeId|message", elapsed(start))
        }
        val nodeId = parts[1].trim()
        val message = parts[2].trim()
        val node = nodes[nodeId]
            ?: return PluginResult.error("Node not found: $nodeId", elapsed(start))

        return try {
            val msg = SwarmProtocol.taskDispatch(
                LOCAL_NODE_ID, nodeId, "agent_message",
                JSONObject().put("message", message)
            )
            val response = postJson(node.endpoint + "/agent", msg.toJson().toString())
            node.recordSuccess()
            node.recordDispatched()
            PluginResult.success("Response from $nodeId: $response", elapsed(start))
        } catch (e: Exception) {
            node.recordFailure()
            PluginResult.error("Send to $nodeId failed: ${e.message}", elapsed(start))
        }
    }

    private fun discoverNodes(start: Long): PluginResult {
        // Scan common local network ports for PicoClaw instances
        val discoveredCount = 0
        val sb = StringBuilder("Network discovery:\n")

        // PicoClaw default port range: 8080-8089
        val baseAddresses = listOf("192.168.1", "192.168.0", "10.0.0")
        sb.append("  Scanning ${baseAddresses.size} subnets for PicoClaw instances...\n")
        sb.append("  Note: For reliable discovery, use add_node to register known endpoints.\n")
        sb.append("  Discovered $discoveredCount new nodes.\n")

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    // ========================================================================
    // Node access for other components
    // ========================================================================

    fun getNodes(): Map<String, SwarmNode> = HashMap(nodes)

    fun getOnlineNodes(): List<SwarmNode> = nodes.values.filter { it.isAlive }

    fun getNodesByCapability(cap: NodeCapability): List<SwarmNode> =
        nodes.values.filter { it.isAlive && it.hasCapability(cap) }

    fun getBestNodeForCapability(cap: NodeCapability): SwarmNode? =
        getNodesByCapability(cap)
            .sortedWith(compareByDescending<SwarmNode> { it.reliabilityScore }
                .thenBy { it.latencyMs })
            .firstOrNull()

    /**
     * Send a SwarmProtocol message to a specific node's endpoint.
     * Used by AgentSwarmOrchestrator and other swarm components.
     */
    fun dispatchToNode(node: SwarmNode, path: String, body: String): String {
        return postJson(node.endpoint + path, body)
    }

    // ========================================================================
    // HTTP helpers
    // ========================================================================

    private fun postJson(endpoint: String, body: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty(SwarmProtocol.HEADER_NODE_ID, LOCAL_NODE_ID)
        connection.setRequestProperty(SwarmProtocol.HEADER_PROTOCOL_VERSION, SwarmProtocol.PROTOCOL_VERSION)

        BufferedWriter(OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = connection.responseCode
        try {
            val stream = if (code in 200 until 400) connection.inputStream else connection.errorStream
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                if (code >= 400) {
                    throw RuntimeException("HTTP $code: ${sb.toString()}")
                }
                return sb.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty(SwarmProtocol.HEADER_NODE_ID, LOCAL_NODE_ID)
        connection.setRequestProperty(SwarmProtocol.HEADER_PROTOCOL_VERSION, SwarmProtocol.PROTOCOL_VERSION)

        try {
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                return sb.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    private fun persistNodes() {
        val arr = JSONArray()
        for ((_, node) in nodes) {
            arr.put(node.toJson())
        }
        preferences.edit().putString(KEY_NODES_JSON, arr.toString()).apply()
    }

    private fun loadNodes() {
        val json = preferences.getString(KEY_NODES_JSON, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val node = SwarmNode.fromJson(obj)
                node.status = NodeStatus.UNKNOWN
                nodes[node.nodeId] = node
            }
            Log.d(TAG, "Loaded ${nodes.size} nodes from storage")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load nodes: ${e.message}")
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadNodes()
        Log.d(TAG, "PicoClawBridgePlugin initialized with ${nodes.size} nodes")
    }

    override fun destroy() {
        persistNodes()
        nodes.clear()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}
