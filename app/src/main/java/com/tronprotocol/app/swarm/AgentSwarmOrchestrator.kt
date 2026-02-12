package com.tronprotocol.app.swarm

import android.util.Log
import com.tronprotocol.app.plugins.PicoClawBridgePlugin
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import com.tronprotocol.app.swarm.SwarmNode.NodeStatus
import com.tronprotocol.app.swarm.SwarmTask.TaskStatus
import com.tronprotocol.app.swarm.SwarmTask.TaskType
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent Swarm Orchestrator — the brain of the distributed PicoClaw swarm.
 *
 * Coordinates task distribution, load balancing, health monitoring, and
 * failover across all registered PicoClaw edge nodes. The orchestrator
 * runs a periodic health check loop and processes a task queue, matching
 * tasks to the best available node based on capability, reliability,
 * and latency.
 *
 * Swarm topology:
 *   TronProtocol (Android) = central coordinator
 *   PicoClaw nodes = satellite agents (inference, gateways, cron, etc.)
 *
 * Scheduling strategy:
 *   1. Capability match — node must have the required capability
 *   2. Reliability-weighted — prefer nodes with higher success rates
 *   3. Latency-aware — prefer lower-latency nodes when reliability is equal
 *   4. Load-balanced — avoid dispatching to BUSY nodes unless no alternative
 */
class AgentSwarmOrchestrator(
    private val bridge: PicoClawBridgePlugin
) {
    companion object {
        private const val TAG = "SwarmOrchestrator"
        private const val LOCAL_NODE_ID = "tronprotocol_android"
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
        private const val TASK_PROCESS_INTERVAL_MS = 1_000L
        private const val STALE_NODE_THRESHOLD_MS = 180_000L
    }

    private val taskQueue = ConcurrentLinkedQueue<SwarmTask>()
    private val activeTasks = ConcurrentHashMap<String, SwarmTask>()
    private val completedTasks = ConcurrentLinkedQueue<SwarmTask>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(3)
    private val running = AtomicBoolean(false)

    private val totalDispatched = AtomicLong(0)
    private val totalCompleted = AtomicLong(0)
    private val totalFailed = AtomicLong(0)

    // Callbacks for routing inference through the swarm
    var onInferenceResult: ((String, String) -> Unit)? = null
    var onGatewayMessage: ((String, String, String) -> Unit)? = null
    var onVoiceTranscription: ((String, String) -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.d(TAG, "Starting agent swarm orchestrator")

        // Periodic health check of all nodes
        scheduler.scheduleAtFixedRate(
            { runHealthChecks() },
            0L, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS
        )

        // Task processing loop
        scheduler.scheduleAtFixedRate(
            { processTaskQueue() },
            500L, TASK_PROCESS_INTERVAL_MS, TimeUnit.MILLISECONDS
        )

        // Stale task reaper
        scheduler.scheduleAtFixedRate(
            { reapStaleTasks() },
            10_000L, 15_000L, TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.d(TAG, "Stopping agent swarm orchestrator")
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            scheduler.shutdownNow()
        }
    }

    // ========================================================================
    // Task submission
    // ========================================================================

    fun submitTask(task: SwarmTask): String {
        taskQueue.add(task)
        Log.d(TAG, "Task queued: ${task.taskId} type=${task.type}")
        return task.taskId
    }

    fun submitInference(prompt: String, model: String? = null, maxTokens: Int = 600): String {
        val metadata = JSONObject().apply {
            if (model != null) put("model", model)
            put("max_tokens", maxTokens)
        }
        val task = SwarmTask(
            type = TaskType.INFERENCE,
            input = prompt,
            metadata = metadata,
            priority = 30,
            timeoutMs = 60_000L
        )
        return submitTask(task)
    }

    fun submitGatewaySend(platform: String, chatId: String, text: String): String {
        val metadata = JSONObject().apply {
            put("platform", platform)
            put("chat_id", chatId)
        }
        val task = SwarmTask(
            type = TaskType.GATEWAY_SEND,
            input = text,
            metadata = metadata,
            priority = 20
        )
        return submitTask(task)
    }

    fun submitVoiceTranscription(audioUrl: String, language: String = "en"): String {
        val metadata = JSONObject().apply {
            put("audio_url", audioUrl)
            put("language", language)
        }
        val task = SwarmTask(
            type = TaskType.VOICE_TRANSCRIBE,
            input = audioUrl,
            metadata = metadata,
            priority = 25,
            timeoutMs = 45_000L
        )
        return submitTask(task)
    }

    fun submitSkillExecution(skillName: String, args: String): String {
        val metadata = JSONObject().apply {
            put("skill_name", skillName)
        }
        val task = SwarmTask(
            type = TaskType.SKILL_EXECUTE,
            input = args,
            metadata = metadata,
            priority = 40
        )
        return submitTask(task)
    }

    fun submitMemorySync(content: String, relevance: Float): String {
        val metadata = JSONObject().apply {
            put("relevance", relevance.toDouble())
        }
        val task = SwarmTask(
            type = TaskType.MEMORY_SYNC,
            input = content,
            metadata = metadata,
            priority = 60
        )
        return submitTask(task)
    }

    fun getTaskStatus(taskId: String): SwarmTask? =
        activeTasks[taskId] ?: completedTasks.find { it.taskId == taskId }

    // ========================================================================
    // Task processing
    // ========================================================================

    private fun processTaskQueue() {
        if (!running.get()) return

        var processed = 0
        while (processed < 10) {
            val task = taskQueue.poll() ?: break
            processed++
            dispatchTask(task)
        }
    }

    private fun dispatchTask(task: SwarmTask) {
        val requiredCapability = mapTaskToCapability(task.type)
        val candidateNodes = if (requiredCapability != null) {
            bridge.getNodesByCapability(requiredCapability)
        } else {
            bridge.getOnlineNodes()
        }

        if (candidateNodes.isEmpty()) {
            task.markFailed("No nodes available for ${task.type} (required: $requiredCapability)")
            completedTasks.add(task)
            totalFailed.incrementAndGet()
            Log.w(TAG, "No nodes for task ${task.taskId}: ${task.error}")
            return
        }

        // Select best node: reliability-weighted, latency-aware
        val selectedNode = candidateNodes
            .sortedWith(
                compareByDescending<SwarmNode> { it.reliabilityScore }
                    .thenBy { if (it.status == NodeStatus.BUSY) 1 else 0 }
                    .thenBy { it.latencyMs }
            )
            .first()

        task.markDispatched(selectedNode.nodeId)
        activeTasks[task.taskId] = task
        totalDispatched.incrementAndGet()

        // Execute dispatch asynchronously
        scheduler.submit {
            executeDispatch(task, selectedNode)
        }
    }

    private fun executeDispatch(task: SwarmTask, node: SwarmNode) {
        try {
            task.markRunning()
            val message = buildDispatchMessage(task, node)
            val path = mapTaskToEndpoint(task.type)

            val response = bridge.dispatchToNode(node, path, message.toJson().toString())
            node.recordSuccess()
            node.recordDispatched()

            task.markCompleted(response)
            activeTasks.remove(task.taskId)
            completedTasks.add(task)
            totalCompleted.incrementAndGet()

            // Invoke callbacks
            deliverResult(task, response)

            Log.d(TAG, "Task ${task.taskId} completed on ${node.nodeId} (${task.elapsedMs}ms)")
        } catch (e: Exception) {
            node.recordFailure()
            task.markFailed(e.message ?: "Unknown error")

            if (task.isRetryable) {
                task.retryCount++
                task.status = TaskStatus.PENDING
                taskQueue.add(task)
                activeTasks.remove(task.taskId)
                Log.w(TAG, "Task ${task.taskId} failed on ${node.nodeId}, retrying (${task.retryCount}/${task.maxRetries})")
            } else {
                activeTasks.remove(task.taskId)
                completedTasks.add(task)
                totalFailed.incrementAndGet()
                Log.e(TAG, "Task ${task.taskId} permanently failed: ${e.message}")
            }
        }
    }

    private fun buildDispatchMessage(task: SwarmTask, node: SwarmNode): SwarmProtocol.SwarmMessage {
        return when (task.type) {
            TaskType.INFERENCE -> SwarmProtocol.inferenceRequest(
                LOCAL_NODE_ID,
                node.nodeId,
                task.input,
                task.metadata.optString("model", null),
                task.metadata.optInt("max_tokens", 600)
            )
            TaskType.GATEWAY_SEND -> SwarmProtocol.gatewayMessage(
                LOCAL_NODE_ID,
                node.nodeId,
                task.metadata.optString("platform", "telegram"),
                task.metadata.optString("chat_id", ""),
                task.input
            )
            TaskType.VOICE_TRANSCRIBE -> SwarmProtocol.voiceTranscribeRequest(
                LOCAL_NODE_ID,
                node.nodeId,
                task.metadata.optString("audio_url", task.input),
                task.metadata.optString("language", "en")
            )
            TaskType.SKILL_EXECUTE -> SwarmProtocol.skillInvoke(
                LOCAL_NODE_ID,
                node.nodeId,
                task.metadata.optString("skill_name", ""),
                task.input
            )
            TaskType.MEMORY_SYNC -> SwarmProtocol.memoryPush(
                LOCAL_NODE_ID,
                node.nodeId,
                task.input,
                task.metadata.optDouble("relevance", 0.5).toFloat()
            )
            else -> SwarmProtocol.taskDispatch(
                LOCAL_NODE_ID,
                node.nodeId,
                task.type.name,
                JSONObject().put("input", task.input).put("metadata", task.metadata)
            )
        }
    }

    private fun deliverResult(task: SwarmTask, response: String) {
        try {
            when (task.type) {
                TaskType.INFERENCE -> onInferenceResult?.invoke(task.taskId, response)
                TaskType.VOICE_TRANSCRIBE -> onVoiceTranscription?.invoke(task.taskId, response)
                TaskType.GATEWAY_SEND -> {
                    val platform = task.metadata.optString("platform", "unknown")
                    onGatewayMessage?.invoke(task.taskId, platform, response)
                }
                else -> { /* no callback for other types */ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Callback delivery failed for ${task.taskId}: ${e.message}")
        }
    }

    // ========================================================================
    // Health checks
    // ========================================================================

    private fun runHealthChecks() {
        if (!running.get()) return
        val nodes = bridge.getNodes()
        if (nodes.isEmpty()) return

        Log.d(TAG, "Running health checks on ${nodes.size} nodes")
        for ((_, node) in nodes) {
            try {
                val pingStart = System.currentTimeMillis()
                val msg = SwarmProtocol.ping(LOCAL_NODE_ID)
                bridge.dispatchToNode(node, "/ping", msg.toJson().toString())

                node.latencyMs = System.currentTimeMillis() - pingStart
                node.lastHeartbeat = System.currentTimeMillis()
                node.status = NodeStatus.ONLINE
                node.recordSuccess()
            } catch (e: Exception) {
                val timeSinceLastSeen = System.currentTimeMillis() - node.lastHeartbeat
                node.status = if (timeSinceLastSeen > STALE_NODE_THRESHOLD_MS) {
                    NodeStatus.OFFLINE
                } else {
                    NodeStatus.DEGRADED
                }
                node.recordFailure()
            }
        }
    }

    private fun reapStaleTasks() {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        val staleIds = mutableListOf<String>()

        for ((id, task) in activeTasks) {
            if (task.dispatchedAt > 0 && (now - task.dispatchedAt) > task.timeoutMs) {
                task.markTimedOut()
                staleIds.add(id)
                totalFailed.incrementAndGet()
            }
        }

        for (id in staleIds) {
            val task = activeTasks.remove(id)
            if (task != null) {
                if (task.isRetryable) {
                    task.retryCount++
                    task.status = TaskStatus.PENDING
                    taskQueue.add(task)
                    Log.w(TAG, "Task $id timed out, retrying (${task.retryCount}/${task.maxRetries})")
                } else {
                    completedTasks.add(task)
                    Log.w(TAG, "Task $id permanently timed out")
                }
            }
        }

        // Trim completed tasks (keep last 200)
        while (completedTasks.size > 200) {
            completedTasks.poll()
        }
    }

    // ========================================================================
    // Mapping helpers
    // ========================================================================

    private fun mapTaskToCapability(type: TaskType): NodeCapability? = when (type) {
        TaskType.INFERENCE -> NodeCapability.INFERENCE
        TaskType.GATEWAY_SEND, TaskType.GATEWAY_FETCH -> null // gateway capability varies by platform
        TaskType.VOICE_TRANSCRIBE -> NodeCapability.VOICE_TRANSCRIPTION
        TaskType.SKILL_EXECUTE -> NodeCapability.SKILL_EXECUTION
        TaskType.MEMORY_SYNC -> NodeCapability.MEMORY_STORAGE
        TaskType.WEB_SEARCH -> NodeCapability.WEB_SEARCH
        TaskType.CRON_SCHEDULE -> NodeCapability.CRON_SCHEDULING
        TaskType.HEALTH_CHECK -> null
        TaskType.NODE_COMMAND -> null
    }

    private fun mapTaskToEndpoint(type: TaskType): String = when (type) {
        TaskType.INFERENCE -> "/agent"
        TaskType.GATEWAY_SEND -> "/gateway/send"
        TaskType.GATEWAY_FETCH -> "/gateway/fetch"
        TaskType.VOICE_TRANSCRIBE -> "/voice/transcribe"
        TaskType.SKILL_EXECUTE -> "/skills/invoke"
        TaskType.MEMORY_SYNC -> "/memory/push"
        TaskType.WEB_SEARCH -> "/search"
        TaskType.CRON_SCHEDULE -> "/cron"
        TaskType.HEALTH_CHECK -> "/ping"
        TaskType.NODE_COMMAND -> "/agent"
    }

    // ========================================================================
    // Stats
    // ========================================================================

    fun getStats(): Map<String, Any> {
        val onlineNodes = bridge.getOnlineNodes().size
        val totalNodes = bridge.getNodes().size
        return mapOf(
            "total_nodes" to totalNodes,
            "online_nodes" to onlineNodes,
            "queued_tasks" to taskQueue.size,
            "active_tasks" to activeTasks.size,
            "completed_tasks" to completedTasks.size,
            "total_dispatched" to totalDispatched.get(),
            "total_completed" to totalCompleted.get(),
            "total_failed" to totalFailed.get(),
            "running" to running.get()
        )
    }

    fun getTopology(): JSONObject = JSONObject().apply {
        put("coordinator", JSONObject().apply {
            put("node_id", LOCAL_NODE_ID)
            put("role", "coordinator")
            put("platform", "android")
        })
        val nodesArray = org.json.JSONArray()
        for ((_, node) in bridge.getNodes()) {
            nodesArray.put(node.toJson())
        }
        put("nodes", nodesArray)
        put("stats", JSONObject(getStats().mapValues { it.value.toString() }))
    }
}
