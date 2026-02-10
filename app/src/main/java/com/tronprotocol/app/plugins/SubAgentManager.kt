package com.tronprotocol.app.plugins

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sub-Agent Manager — isolated sub-task spawning with bounded resources.
 *
 * Inspired by OpenClaw's sub-agent system:
 * - Spawned via sessions_spawn (non-blocking, returns immediately)
 * - Each sub-agent runs in an isolated session
 * - Results announced back to parent with metrics (runtime, tokens, cost)
 * - Default max concurrency: 8 simultaneous sub-agents
 * - No nested spawning (prevents runaway fan-out)
 * - Restricted capabilities (no dangerous tools)
 * - Auto-archive after timeout
 *
 * In TronProtocol, sub-agents allow plugins to delegate work to other plugins
 * in isolated contexts without risking interference with the main service.
 */
class SubAgentManager(
    private val pluginManager: PluginManager,
    private val maxConcurrentAgents: Int = DEFAULT_MAX_CONCURRENT,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    /** Request to spawn a sub-agent. */
    data class SpawnRequest(
        val parentPluginId: String,
        val targetPluginId: String,
        val input: String,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val isolationLevel: IsolationLevel = IsolationLevel.STANDARD
    )

    enum class IsolationLevel {
        MINIMAL,   // Shared context, no dangerous tools
        STANDARD,  // Isolated context, restricted tools
        STRICT     // Fully isolated, minimal tools only
    }

    /** Result from a completed sub-agent execution. */
    data class SubAgentResult(
        val agentId: String,
        val parentPluginId: String,
        val targetPluginId: String,
        val status: Status,
        val result: PluginResult?,
        val runtimeMs: Long,
        val error: String?
    ) {
        enum class Status { COMPLETED, FAILED, TIMED_OUT, CANCELLED, REJECTED }
    }

    /** Callback for sub-agent completion. */
    fun interface CompletionCallback {
        fun onComplete(result: SubAgentResult)
    }

    // Active sub-agent tracking
    private val activeAgents = ConcurrentHashMap<String, SubAgentContext>()
    private val agentHistory = ConcurrentHashMap<String, SubAgentResult>()
    private val activeCount = AtomicInteger(0)

    // Execution and cleanup
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxConcurrentAgents) { r ->
        Thread(r, "SubAgent-Worker").apply { isDaemon = true }
    }
    private val archiveScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SubAgent-Archive").apply { isDaemon = true }
    }

    // Tool deny list per isolation level
    private val toolDenyLists = mapOf(
        IsolationLevel.MINIMAL to setOf("file_manager", "telegram_bridge", "communication_hub"),
        IsolationLevel.STANDARD to setOf(
            "file_manager", "telegram_bridge", "communication_hub",
            "sandbox_exec", "guidance_router"
        ),
        IsolationLevel.STRICT to setOf(
            "file_manager", "telegram_bridge", "communication_hub",
            "sandbox_exec", "guidance_router", "web_search",
            "task_automation", "personalization"
        )
    )

    init {
        // Schedule periodic cleanup of expired agents
        archiveScheduler.scheduleAtFixedRate(
            { archiveExpiredAgents() },
            ARCHIVE_CHECK_INTERVAL_MS, ARCHIVE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    /**
     * Spawn a sub-agent to execute a plugin task asynchronously.
     * Returns immediately with an agent ID.
     */
    fun spawn(request: SpawnRequest, callback: CompletionCallback? = null): String? {
        // Enforce concurrency limit
        if (activeCount.get() >= maxConcurrentAgents) {
            Log.w(TAG, "Cannot spawn sub-agent: concurrency limit reached ($maxConcurrentAgents)")
            return null
        }

        // Prevent nested spawning
        if (isSubAgent(request.parentPluginId)) {
            Log.w(TAG, "Nested spawning blocked: ${request.parentPluginId} is already a sub-agent")
            return null
        }

        // Check tool deny list
        val denyList = toolDenyLists[request.isolationLevel] ?: emptySet()
        if (request.targetPluginId in denyList) {
            Log.w(TAG, "Sub-agent denied access to ${request.targetPluginId} " +
                    "at isolation level ${request.isolationLevel}")
            callback?.onComplete(SubAgentResult(
                agentId = "",
                parentPluginId = request.parentPluginId,
                targetPluginId = request.targetPluginId,
                status = SubAgentResult.Status.REJECTED,
                result = null,
                runtimeMs = 0,
                error = "Plugin ${request.targetPluginId} denied at ${request.isolationLevel} isolation"
            ))
            return null
        }

        val agentId = "subagent_${UUID.randomUUID().toString().take(8)}"
        val context = SubAgentContext(
            agentId = agentId,
            request = request,
            callback = callback,
            spawnedAt = System.currentTimeMillis()
        )

        activeAgents[agentId] = context
        activeCount.incrementAndGet()

        Log.d(TAG, "Spawning sub-agent $agentId: ${request.parentPluginId} -> " +
                "${request.targetPluginId} (isolation=${request.isolationLevel})")

        // Execute asynchronously
        context.future = executor.submit {
            executeSubAgent(context)
        }

        return agentId
    }

    /**
     * Spawn and wait for result synchronously (with timeout).
     */
    fun spawnAndWait(request: SpawnRequest): SubAgentResult {
        var result: SubAgentResult? = null
        val lock = Object()

        val agentId = spawn(request) { r ->
            synchronized(lock) {
                result = r
                lock.notifyAll()
            }
        }

        if (agentId == null) {
            return SubAgentResult(
                agentId = "", parentPluginId = request.parentPluginId,
                targetPluginId = request.targetPluginId,
                status = SubAgentResult.Status.REJECTED,
                result = null, runtimeMs = 0,
                error = "Failed to spawn sub-agent"
            )
        }

        synchronized(lock) {
            if (result == null) {
                lock.wait(request.timeoutMs)
            }
        }

        return result ?: SubAgentResult(
            agentId = agentId, parentPluginId = request.parentPluginId,
            targetPluginId = request.targetPluginId,
            status = SubAgentResult.Status.TIMED_OUT,
            result = null, runtimeMs = request.timeoutMs,
            error = "Sub-agent timed out after ${request.timeoutMs}ms"
        )
    }

    /**
     * Cancel a running sub-agent.
     */
    fun cancel(agentId: String): Boolean {
        val context = activeAgents[agentId] ?: return false
        context.future?.cancel(true)
        completeAgent(context, SubAgentResult(
            agentId = agentId,
            parentPluginId = context.request.parentPluginId,
            targetPluginId = context.request.targetPluginId,
            status = SubAgentResult.Status.CANCELLED,
            result = null,
            runtimeMs = System.currentTimeMillis() - context.spawnedAt,
            error = "Cancelled by parent"
        ))
        return true
    }

    /**
     * Check if a plugin ID represents a sub-agent (to prevent nesting).
     */
    private fun isSubAgent(pluginId: String): Boolean {
        return activeAgents.values.any { it.request.targetPluginId == pluginId }
    }

    /**
     * Get status of active sub-agents.
     */
    fun getActiveAgents(): Map<String, Map<String, Any>> {
        return activeAgents.mapValues { (_, ctx) ->
            mapOf(
                "parent" to ctx.request.parentPluginId,
                "target" to ctx.request.targetPluginId,
                "isolation" to ctx.request.isolationLevel.name,
                "runtime_ms" to (System.currentTimeMillis() - ctx.spawnedAt),
                "spawned_at" to ctx.spawnedAt
            )
        }
    }

    /**
     * Get historical sub-agent results.
     */
    fun getHistory(): List<SubAgentResult> {
        return agentHistory.values.toList().sortedByDescending { it.runtimeMs }
    }

    /**
     * Get aggregate statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "active_agents" to activeCount.get(),
        "max_concurrent" to maxConcurrentAgents,
        "total_completed" to agentHistory.size,
        "total_succeeded" to agentHistory.values.count { it.status == SubAgentResult.Status.COMPLETED },
        "total_failed" to agentHistory.values.count { it.status == SubAgentResult.Status.FAILED },
        "total_timed_out" to agentHistory.values.count { it.status == SubAgentResult.Status.TIMED_OUT },
        "total_cancelled" to agentHistory.values.count { it.status == SubAgentResult.Status.CANCELLED },
        "total_rejected" to agentHistory.values.count { it.status == SubAgentResult.Status.REJECTED }
    )

    /**
     * Shut down the sub-agent manager.
     */
    fun shutdown() {
        activeAgents.values.forEach { it.future?.cancel(true) }
        activeAgents.clear()
        executor.shutdown()
        archiveScheduler.shutdown()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
        Log.d(TAG, "SubAgentManager shut down. Stats: ${getStats()}")
    }

    // -- Internal execution --

    private fun executeSubAgent(context: SubAgentContext) {
        val startTime = System.currentTimeMillis()
        try {
            val pluginResult = pluginManager.executePlugin(
                context.request.targetPluginId,
                context.request.input
            )

            val runtimeMs = System.currentTimeMillis() - startTime
            val status = if (pluginResult.isSuccess) {
                SubAgentResult.Status.COMPLETED
            } else {
                SubAgentResult.Status.FAILED
            }

            val result = SubAgentResult(
                agentId = context.agentId,
                parentPluginId = context.request.parentPluginId,
                targetPluginId = context.request.targetPluginId,
                status = status,
                result = pluginResult,
                runtimeMs = runtimeMs,
                error = if (!pluginResult.isSuccess) pluginResult.errorMessage else null
            )

            Log.d(TAG, "Sub-agent ${context.agentId} completed: status=$status runtime=${runtimeMs}ms")
            completeAgent(context, result)
        } catch (e: Exception) {
            val runtimeMs = System.currentTimeMillis() - startTime
            val result = SubAgentResult(
                agentId = context.agentId,
                parentPluginId = context.request.parentPluginId,
                targetPluginId = context.request.targetPluginId,
                status = SubAgentResult.Status.FAILED,
                result = null,
                runtimeMs = runtimeMs,
                error = "Sub-agent execution error: ${e.message}"
            )
            Log.e(TAG, "Sub-agent ${context.agentId} failed", e)
            completeAgent(context, result)
        }
    }

    private fun completeAgent(context: SubAgentContext, result: SubAgentResult) {
        activeAgents.remove(context.agentId)
        activeCount.decrementAndGet()
        agentHistory[context.agentId] = result
        context.callback?.onComplete(result)
    }

    private fun archiveExpiredAgents() {
        val now = System.currentTimeMillis()
        val expired = activeAgents.filter { (_, ctx) ->
            now - ctx.spawnedAt > ctx.request.timeoutMs
        }

        for ((agentId, context) in expired) {
            Log.w(TAG, "Archiving expired sub-agent: $agentId")
            context.future?.cancel(true)
            completeAgent(context, SubAgentResult(
                agentId = agentId,
                parentPluginId = context.request.parentPluginId,
                targetPluginId = context.request.targetPluginId,
                status = SubAgentResult.Status.TIMED_OUT,
                result = null,
                runtimeMs = now - context.spawnedAt,
                error = "Expired after ${context.request.timeoutMs}ms"
            ))
        }
    }

    /** Internal context for tracking a sub-agent execution. */
    private data class SubAgentContext(
        val agentId: String,
        val request: SpawnRequest,
        val callback: CompletionCallback?,
        val spawnedAt: Long,
        var future: Future<*>? = null
    )

    companion object {
        private const val TAG = "SubAgentManager"
        const val DEFAULT_MAX_CONCURRENT = 8
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val ARCHIVE_CHECK_INTERVAL_MS = 30_000L
    }
}
