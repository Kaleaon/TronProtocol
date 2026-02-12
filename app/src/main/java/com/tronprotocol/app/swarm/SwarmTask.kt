package com.tronprotocol.app.swarm

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a task dispatched across the agent swarm.
 *
 * Tasks follow a lifecycle: PENDING -> DISPATCHED -> RUNNING -> COMPLETED/FAILED/CANCELLED.
 * The orchestrator tracks all tasks and handles retries on failure.
 */
data class SwarmTask(
    val taskId: String = UUID.randomUUID().toString(),
    val type: TaskType,
    val input: String,
    val metadata: JSONObject = JSONObject(),
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 50,
    val maxRetries: Int = 2,
    val timeoutMs: Long = 30_000L
) {
    enum class TaskType {
        INFERENCE,
        GATEWAY_SEND,
        GATEWAY_FETCH,
        VOICE_TRANSCRIBE,
        SKILL_EXECUTE,
        MEMORY_SYNC,
        WEB_SEARCH,
        CRON_SCHEDULE,
        HEALTH_CHECK,
        NODE_COMMAND
    }

    enum class TaskStatus {
        PENDING,
        DISPATCHED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    @Volatile var status: TaskStatus = TaskStatus.PENDING
    @Volatile var assignedNodeId: String? = null
    @Volatile var result: String? = null
    @Volatile var error: String? = null
    @Volatile var dispatchedAt: Long = 0L
    @Volatile var completedAt: Long = 0L
    @Volatile var retryCount: Int = 0

    val isTerminal: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED || status == TaskStatus.TIMED_OUT

    val isRetryable: Boolean
        get() = status == TaskStatus.FAILED && retryCount < maxRetries

    val elapsedMs: Long
        get() = if (dispatchedAt > 0) {
            (if (completedAt > 0) completedAt else System.currentTimeMillis()) - dispatchedAt
        } else 0L

    fun markDispatched(nodeId: String) {
        status = TaskStatus.DISPATCHED
        assignedNodeId = nodeId
        dispatchedAt = System.currentTimeMillis()
    }

    fun markRunning() {
        status = TaskStatus.RUNNING
    }

    fun markCompleted(taskResult: String) {
        status = TaskStatus.COMPLETED
        result = taskResult
        completedAt = System.currentTimeMillis()
    }

    fun markFailed(taskError: String) {
        status = TaskStatus.FAILED
        error = taskError
        completedAt = System.currentTimeMillis()
    }

    fun markCancelled() {
        status = TaskStatus.CANCELLED
        completedAt = System.currentTimeMillis()
    }

    fun markTimedOut() {
        status = TaskStatus.TIMED_OUT
        error = "Task timed out after ${timeoutMs}ms"
        completedAt = System.currentTimeMillis()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("task_id", taskId)
        put("type", type.name)
        put("input", input)
        put("status", status.name)
        put("priority", priority)
        put("assigned_node", assignedNodeId ?: JSONObject.NULL)
        put("result", result ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("created_at", createdAt)
        put("dispatched_at", dispatchedAt)
        put("completed_at", completedAt)
        put("retry_count", retryCount)
        put("elapsed_ms", elapsedMs)
    }
}
