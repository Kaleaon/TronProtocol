package com.tronprotocol.app.swarm

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a PicoClaw edge node in the agent swarm.
 *
 * Each node is a lightweight PicoClaw instance running on edge hardware
 * (RISC-V, ARM64, etc.) that communicates with TronProtocol via HTTP/REST.
 */
data class SwarmNode(
    val nodeId: String,
    val displayName: String,
    val endpoint: String,
    val architecture: Architecture = Architecture.UNKNOWN,
    val capabilities: Set<NodeCapability> = emptySet()
) {
    enum class Architecture { RISCV64, ARM64, AMD64, UNKNOWN }

    enum class NodeCapability {
        INFERENCE,
        GATEWAY_TELEGRAM,
        GATEWAY_DISCORD,
        GATEWAY_QQ,
        GATEWAY_DINGTALK,
        WEB_SEARCH,
        CRON_SCHEDULING,
        VOICE_TRANSCRIPTION,
        FILE_EDITING,
        SHELL_EXECUTION,
        SKILL_EXECUTION,
        MEMORY_STORAGE
    }

    enum class NodeStatus { ONLINE, OFFLINE, DEGRADED, BUSY, UNKNOWN }

    @Volatile var status: NodeStatus = NodeStatus.UNKNOWN
    @Volatile var lastHeartbeat: Long = 0L
    @Volatile var latencyMs: Long = -1L
    @Volatile var picoClawVersion: String = "unknown"
    @Volatile var activeModel: String? = null
    @Volatile var memoryUsageMb: Int = 0

    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val totalTasksDispatched = AtomicLong(0)

    val isAlive: Boolean
        get() = status == NodeStatus.ONLINE || status == NodeStatus.BUSY

    val reliabilityScore: Float
        get() {
            val total = successCount.get() + failureCount.get()
            return if (total == 0) 1.0f else successCount.get().toFloat() / total
        }

    fun recordSuccess() { successCount.incrementAndGet() }
    fun recordFailure() { failureCount.incrementAndGet() }
    fun recordDispatched() { totalTasksDispatched.incrementAndGet() }

    fun hasCapability(cap: NodeCapability): Boolean = cap in capabilities

    fun toJson(): JSONObject = JSONObject().apply {
        put("node_id", nodeId)
        put("display_name", displayName)
        put("endpoint", endpoint)
        put("architecture", architecture.name)
        put("status", status.name)
        put("last_heartbeat", lastHeartbeat)
        put("latency_ms", latencyMs)
        put("picoclaw_version", picoClawVersion)
        put("active_model", activeModel ?: "none")
        put("memory_usage_mb", memoryUsageMb)
        put("reliability", reliabilityScore)
        put("tasks_dispatched", totalTasksDispatched.get())
        put("capabilities", capabilities.joinToString(",") { it.name })
    }

    fun getStats(): Map<String, Any> = mapOf(
        "node_id" to nodeId,
        "status" to status.name,
        "latency_ms" to latencyMs,
        "reliability" to reliabilityScore,
        "successes" to successCount.get(),
        "failures" to failureCount.get(),
        "tasks_dispatched" to totalTasksDispatched.get()
    )

    companion object {
        fun fromJson(json: JSONObject): SwarmNode {
            val caps = json.optString("capabilities", "")
                .split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { name ->
                    try { NodeCapability.valueOf(name.trim()) }
                    catch (_: Exception) { null }
                }
                .toSet()

            val arch = try {
                Architecture.valueOf(json.optString("architecture", "UNKNOWN"))
            } catch (_: Exception) { Architecture.UNKNOWN }

            return SwarmNode(
                nodeId = json.getString("node_id"),
                displayName = json.optString("display_name", json.getString("node_id")),
                endpoint = json.getString("endpoint"),
                architecture = arch,
                capabilities = caps
            ).also {
                it.picoClawVersion = json.optString("picoclaw_version", "unknown")
                it.activeModel = json.optString("active_model", null)
                it.memoryUsageMb = json.optInt("memory_usage_mb", 0)
            }
        }
    }
}
