package com.tronprotocol.app.plugins

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Lane Queue Executor — serial-by-default concurrency control for plugin execution.
 *
 * Inspired by OpenClaw's Lane Queue system:
 * - Each session/context gets its own "lane"
 * - Tasks within a lane execute sequentially (preventing state corruption)
 * - Only explicitly marked idempotent/safe tasks run in a parallel lane
 * - Controlled concurrency with configurable limits
 *
 * This prevents race conditions when multiple plugins access shared state
 * (RAG store, secure storage, preferences) while still allowing safe
 * operations to run concurrently for performance.
 */
class LaneQueueExecutor(
    private val maxParallelLanes: Int = DEFAULT_MAX_PARALLEL_LANES
) {

    /** Represents a task submitted to a lane. */
    data class LaneTask(
        val taskId: String,
        val laneId: String,
        val pluginId: String,
        val input: String,
        val parallel: Boolean = false,
        val priority: Int = PRIORITY_NORMAL,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS
    )

    /** Result of a lane task execution. */
    data class LaneTaskResult(
        val taskId: String,
        val laneId: String,
        val pluginId: String,
        val result: PluginResult?,
        val error: String?,
        val queueTimeMs: Long,
        val executionTimeMs: Long
    ) {
        val isSuccess: Boolean get() = result?.isSuccess == true && error == null
    }

    /** Callback for async task completion. */
    fun interface TaskCompletionListener {
        fun onComplete(result: LaneTaskResult)
    }

    // Per-lane serial queues
    private val lanes = ConcurrentHashMap<String, LaneQueue>()
    private val laneLocks = ConcurrentHashMap<String, ReentrantLock>()

    // Parallel lane for idempotent tasks
    private val parallelExecutor: ExecutorService =
        Executors.newFixedThreadPool(maxParallelLanes) { r ->
            Thread(r, "LaneQueue-Parallel").apply { isDaemon = true }
        }

    // Metrics
    private val totalTasksSubmitted = AtomicInteger(0)
    private val totalTasksCompleted = AtomicInteger(0)
    private val totalTasksFailed = AtomicInteger(0)
    private val activeLaneCount = AtomicInteger(0)

    // Plugin manager reference for execution
    private var pluginManager: PluginManager? = null

    fun setPluginManager(manager: PluginManager) {
        this.pluginManager = manager
    }

    /**
     * Submit a task to the appropriate lane.
     * Serial tasks queue behind other tasks in their lane.
     * Parallel tasks execute immediately on the parallel pool.
     */
    fun submit(task: LaneTask, listener: TaskCompletionListener? = null): Future<LaneTaskResult> {
        totalTasksSubmitted.incrementAndGet()
        val queuedAt = System.currentTimeMillis()

        return if (task.parallel) {
            submitParallel(task, queuedAt, listener)
        } else {
            submitSerial(task, queuedAt, listener)
        }
    }

    /**
     * Submit a task and block until completion (with timeout).
     */
    fun submitAndWait(task: LaneTask, timeoutMs: Long = task.timeoutMs): LaneTaskResult {
        val future = submit(task)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            LaneTaskResult(
                taskId = task.taskId,
                laneId = task.laneId,
                pluginId = task.pluginId,
                result = null,
                error = "Task timed out or interrupted: ${e.message}",
                queueTimeMs = 0,
                executionTimeMs = 0
            )
        }
    }

    /**
     * Acquire a write lock on a lane, preventing any other task from executing
     * in that lane until released. Used for multi-step atomic operations.
     */
    fun acquireLaneWriteLock(laneId: String): Boolean {
        val lock = laneLocks.getOrPut(laneId) { ReentrantLock(true) }
        return try {
            lock.tryLock(LANE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Release a previously acquired lane write lock.
     */
    fun releaseLaneWriteLock(laneId: String) {
        laneLocks[laneId]?.let { lock ->
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    /**
     * Get current queue depth for a lane.
     */
    fun getLaneDepth(laneId: String): Int {
        return lanes[laneId]?.pendingCount ?: 0
    }

    /**
     * Get execution statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_submitted" to totalTasksSubmitted.get(),
        "total_completed" to totalTasksCompleted.get(),
        "total_failed" to totalTasksFailed.get(),
        "active_lanes" to activeLaneCount.get(),
        "lane_ids" to lanes.keys.toList(),
        "max_parallel" to maxParallelLanes
    )

    /**
     * Shut down all lanes and the parallel executor.
     */
    fun shutdown() {
        lanes.values.forEach { it.shutdown() }
        lanes.clear()
        parallelExecutor.shutdown()
        try {
            if (!parallelExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                parallelExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            parallelExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        Log.d(TAG, "LaneQueueExecutor shut down. Stats: ${getStats()}")
    }

    // -- Internal execution --

    private fun submitSerial(
        task: LaneTask,
        queuedAt: Long,
        listener: TaskCompletionListener?
    ): Future<LaneTaskResult> {
        val lane = lanes.getOrPut(task.laneId) {
            activeLaneCount.incrementAndGet()
            LaneQueue(task.laneId)
        }
        return lane.enqueue {
            val result = executeTask(task, queuedAt)
            listener?.onComplete(result)
            result
        }
    }

    private fun submitParallel(
        task: LaneTask,
        queuedAt: Long,
        listener: TaskCompletionListener?
    ): Future<LaneTaskResult> {
        return parallelExecutor.submit<LaneTaskResult> {
            val result = executeTask(task, queuedAt)
            listener?.onComplete(result)
            result
        }
    }

    private fun executeTask(task: LaneTask, queuedAt: Long): LaneTaskResult {
        val queueTime = System.currentTimeMillis() - queuedAt
        val execStart = System.currentTimeMillis()

        return try {
            val manager = pluginManager
                ?: return LaneTaskResult(
                    task.taskId, task.laneId, task.pluginId,
                    null, "PluginManager not set", queueTime, 0
                )

            val result = manager.executePlugin(task.pluginId, task.input)
            val execTime = System.currentTimeMillis() - execStart

            totalTasksCompleted.incrementAndGet()
            Log.d(TAG, "Lane[${task.laneId}] task=${task.taskId} plugin=${task.pluginId} " +
                    "queue=${queueTime}ms exec=${execTime}ms success=${result.isSuccess}")

            LaneTaskResult(task.taskId, task.laneId, task.pluginId, result, null, queueTime, execTime)
        } catch (e: Exception) {
            val execTime = System.currentTimeMillis() - execStart
            totalTasksFailed.incrementAndGet()
            Log.e(TAG, "Lane[${task.laneId}] task=${task.taskId} failed", e)

            LaneTaskResult(
                task.taskId, task.laneId, task.pluginId,
                null, "Execution error: ${e.message}", queueTime, execTime
            )
        }
    }

    /**
     * A serial execution lane — tasks enqueue and execute one at a time.
     */
    private inner class LaneQueue(val laneId: String) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "LaneQueue-$laneId").apply { isDaemon = true }
        }
        private val pending = AtomicInteger(0)

        val pendingCount: Int get() = pending.get()

        fun <T> enqueue(task: () -> T): Future<T> {
            pending.incrementAndGet()
            return executor.submit<T> {
                try {
                    task()
                } finally {
                    pending.decrementAndGet()
                }
            }
        }

        fun shutdown() {
            executor.shutdown()
            activeLaneCount.decrementAndGet()
        }
    }

    companion object {
        private const val TAG = "LaneQueueExecutor"
        const val DEFAULT_MAX_PARALLEL_LANES = 4
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val LANE_LOCK_TIMEOUT_MS = 10_000L
        const val PRIORITY_HIGH = 0
        const val PRIORITY_NORMAL = 5
        const val PRIORITY_LOW = 10

        // Well-known lane IDs
        const val LANE_MAIN = "main"
        const val LANE_BACKGROUND = "background"
        const val LANE_GUIDANCE = "guidance"
        const val LANE_MEMORY = "memory"

        // Plugins safe for parallel execution (stateless/idempotent)
        val PARALLEL_SAFE_PLUGINS = setOf(
            "calculator", "datetime", "text_analysis", "device_info"
        )
    }
}
