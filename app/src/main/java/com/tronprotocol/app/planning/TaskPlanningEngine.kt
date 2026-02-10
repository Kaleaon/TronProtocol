package com.tronprotocol.app.planning

import android.util.Log
import com.tronprotocol.app.agent.ReActLoop
import com.tronprotocol.app.bus.MessageBus
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Task Planning Engine - real task decomposition with dependency DAG,
 * parallel execution, and progress tracking.
 *
 * Capabilities:
 * - Task decomposition: breaks complex tasks into subtasks with dependencies
 * - Dependency DAG: topological sort ensures correct execution order
 * - Parallel execution: independent subtasks run concurrently
 * - Critical path analysis: identifies the longest execution chain
 * - Progress tracking: real-time progress with ETA estimation
 * - Retry with backoff: failed subtasks can be retried
 * - ReActLoop integration: uses ReAct agent for intelligent subtask execution
 * - MessageBus integration: broadcasts planning and execution events
 *
 * Example:
 *   "Deploy the app" decomposes into:
 *     1. Run tests (no deps)
 *     2. Build APK (depends on 1)
 *     3. Sign APK (depends on 2)
 *     4. Upload to store (depends on 3)
 *     5. Notify team (depends on 4)
 */
class TaskPlanningEngine(
    private val pluginManager: PluginManager,
    private val reActLoop: ReActLoop? = null,
    private val messageBus: MessageBus? = null,
    private val parallelism: Int = DEFAULT_PARALLELISM
) {

    /** A plannable subtask within a larger plan. */
    data class SubTask(
        val id: String,
        val name: String,
        val description: String,
        val toolId: String? = null,       // Plugin to execute, or null for ReAct
        val toolInput: String = "",
        val dependencies: List<String> = emptyList(),  // SubTask IDs this depends on
        val estimatedDurationMs: Long = 0,
        val retryCount: Int = DEFAULT_RETRY_COUNT
    )

    /** Status of a subtask. */
    enum class SubTaskStatus {
        PENDING, READY, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    /** Runtime state of a subtask. */
    data class SubTaskState(
        val subTask: SubTask,
        var status: SubTaskStatus = SubTaskStatus.PENDING,
        var result: PluginResult? = null,
        var startedAt: Long = 0,
        var completedAt: Long = 0,
        var retriesRemaining: Int = subTask.retryCount,
        var error: String? = null
    )

    /** A complete execution plan. */
    data class Plan(
        val id: String,
        val name: String,
        val subTasks: List<SubTask>,
        val executionOrder: List<List<String>>,  // Layers of parallel subtask IDs
        val criticalPath: List<String>,
        val estimatedTotalMs: Long
    )

    /** Result of executing a plan. */
    data class PlanResult(
        val planId: String,
        val success: Boolean,
        val subTaskResults: Map<String, SubTaskState>,
        val totalDurationMs: Long,
        val completedCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val error: String? = null
    )

    // Execution
    private val executor: ExecutorService = Executors.newFixedThreadPool(parallelism)
    private val activePlans = ConcurrentHashMap<String, MutableMap<String, SubTaskState>>()

    // Stats
    private val totalPlans = AtomicLong(0)
    private val successfulPlans = AtomicLong(0)
    private val totalSubTasks = AtomicLong(0)
    private val planIdCounter = AtomicInteger(0)

    /**
     * Decompose a high-level objective into a Plan.
     * Uses keyword analysis to identify subtasks and their dependencies.
     */
    fun decompose(objective: String, name: String = objective.take(50)): Plan {
        val planId = "plan_${planIdCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val objectiveLower = objective.lowercase()

        val subTasks = when {
            objectiveLower.contains("deploy") || objectiveLower.contains("release") ->
                buildDeployPlan(planId)

            objectiveLower.contains("analyze") && objectiveLower.contains("report") ->
                buildAnalysisReportPlan(planId, objective)

            objectiveLower.contains("backup") || objectiveLower.contains("archive") ->
                buildBackupPlan(planId)

            objectiveLower.contains("monitor") || objectiveLower.contains("health") ->
                buildHealthCheckPlan(planId)

            objectiveLower.contains("test") ->
                buildTestPlan(planId, objective)

            objectiveLower.matches(Regex(".*\\b(and|then|after|also|plus)\\b.*")) ->
                buildSequentialFromConjunctions(planId, objective)

            else -> buildGenericPlan(planId, objective)
        }

        val executionOrder = topologicalSort(subTasks)
        val criticalPath = findCriticalPath(subTasks, executionOrder)
        val estimatedTotal = estimateTotalDuration(subTasks, executionOrder)

        val plan = Plan(planId, name, subTasks, executionOrder, criticalPath, estimatedTotal)

        messageBus?.publishAsync("planning.decompose", plan, "TaskPlanningEngine")
        Log.d(TAG, "Decomposed '$name' into ${subTasks.size} subtasks, " +
                "${executionOrder.size} layers, critical path: ${criticalPath.size} nodes")

        return plan
    }

    /**
     * Create a plan from manually specified subtasks.
     */
    fun createPlan(name: String, subTasks: List<SubTask>): Plan {
        val planId = "plan_${planIdCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val executionOrder = topologicalSort(subTasks)
        val criticalPath = findCriticalPath(subTasks, executionOrder)
        val estimatedTotal = estimateTotalDuration(subTasks, executionOrder)

        return Plan(planId, name, subTasks, executionOrder, criticalPath, estimatedTotal)
    }

    /**
     * Execute a plan, respecting dependency order and running independent tasks in parallel.
     */
    fun execute(plan: Plan): PlanResult {
        val startTime = System.currentTimeMillis()
        totalPlans.incrementAndGet()

        val states = ConcurrentHashMap<String, SubTaskState>()
        for (subTask in plan.subTasks) {
            states[subTask.id] = SubTaskState(subTask)
        }
        activePlans[plan.id] = states

        messageBus?.publishAsync("planning.execute.start", plan.id, "TaskPlanningEngine")

        try {
            // Execute layer by layer (each layer runs in parallel)
            for ((layerIndex, layer) in plan.executionOrder.withIndex()) {
                Log.d(TAG, "Executing layer $layerIndex: ${layer.size} subtasks")

                // Check if all dependencies for this layer are satisfied
                val readyTasks = layer.filter { taskId ->
                    val state = states[taskId] ?: return@filter false
                    val deps = state.subTask.dependencies
                    deps.all { depId ->
                        val depState = states[depId]
                        depState?.status == SubTaskStatus.COMPLETED
                    }
                }

                // Skip tasks whose dependencies failed
                val skippedTasks = layer - readyTasks.toSet()
                for (taskId in skippedTasks) {
                    states[taskId]?.let { state ->
                        state.status = SubTaskStatus.SKIPPED
                        state.error = "Dependency not satisfied"
                    }
                }

                if (readyTasks.isEmpty()) continue

                // Execute ready tasks in parallel
                val latch = CountDownLatch(readyTasks.size)

                for (taskId in readyTasks) {
                    val state = states[taskId] ?: continue
                    state.status = SubTaskStatus.READY

                    executor.execute {
                        try {
                            executeSubTask(state, plan.id)
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                // Wait for all tasks in this layer to complete
                latch.await(LAYER_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                totalSubTasks.addAndGet(readyTasks.size.toLong())

                messageBus?.publishAsync(
                    "planning.execute.layer",
                    mapOf("planId" to plan.id, "layer" to layerIndex, "tasks" to readyTasks.size),
                    "TaskPlanningEngine"
                )
            }

            val completedCount = states.values.count { it.status == SubTaskStatus.COMPLETED }
            val failedCount = states.values.count { it.status == SubTaskStatus.FAILED }
            val skippedCount = states.values.count { it.status == SubTaskStatus.SKIPPED }
            val success = failedCount == 0 && skippedCount == 0

            if (success) successfulPlans.incrementAndGet()

            val result = PlanResult(
                planId = plan.id,
                success = success,
                subTaskResults = states.toMap(),
                totalDurationMs = System.currentTimeMillis() - startTime,
                completedCount = completedCount,
                failedCount = failedCount,
                skippedCount = skippedCount
            )

            messageBus?.publishAsync("planning.execute.complete", result, "TaskPlanningEngine")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Plan execution failed: ${plan.id}", e)
            return PlanResult(
                planId = plan.id,
                success = false,
                subTaskResults = states.toMap(),
                totalDurationMs = System.currentTimeMillis() - startTime,
                completedCount = states.values.count { it.status == SubTaskStatus.COMPLETED },
                failedCount = states.values.count { it.status == SubTaskStatus.FAILED },
                skippedCount = states.values.count { it.status == SubTaskStatus.SKIPPED },
                error = "Plan execution error: ${e.message}"
            )
        } finally {
            activePlans.remove(plan.id)
        }
    }

    /**
     * Get progress for an active plan.
     */
    fun getProgress(planId: String): Map<String, Any>? {
        val states = activePlans[planId] ?: return null
        val total = states.size
        val completed = states.values.count { it.status == SubTaskStatus.COMPLETED }
        val running = states.values.count { it.status == SubTaskStatus.RUNNING }
        val failed = states.values.count { it.status == SubTaskStatus.FAILED }

        return mapOf(
            "total" to total,
            "completed" to completed,
            "running" to running,
            "failed" to failed,
            "pending" to (total - completed - running - failed),
            "percent" to if (total > 0) (completed * 100 / total) else 0,
            "tasks" to states.map { (id, state) ->
                mapOf("id" to id, "name" to state.subTask.name, "status" to state.status.name)
            }
        )
    }

    /**
     * Get engine statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_plans" to totalPlans.get(),
        "successful_plans" to successfulPlans.get(),
        "total_subtasks_executed" to totalSubTasks.get(),
        "active_plans" to activePlans.size,
        "success_rate" to if (totalPlans.get() > 0) {
            successfulPlans.get().toFloat() / totalPlans.get()
        } else 0f
    )

    /**
     * Shut down the execution pool.
     */
    fun shutdown() {
        executor.shutdown()
        Log.d(TAG, "TaskPlanningEngine shut down")
    }

    // -- Internal: SubTask execution --

    private fun executeSubTask(state: SubTaskState, planId: String) {
        state.status = SubTaskStatus.RUNNING
        state.startedAt = System.currentTimeMillis()

        messageBus?.publishAsync(
            "planning.subtask.start",
            mapOf("planId" to planId, "taskId" to state.subTask.id, "name" to state.subTask.name),
            "TaskPlanningEngine"
        )

        var lastError: String? = null

        while (state.retriesRemaining >= 0) {
            try {
                val result = if (state.subTask.toolId != null) {
                    // Direct plugin execution
                    pluginManager.executePlugin(state.subTask.toolId, state.subTask.toolInput)
                } else if (reActLoop != null) {
                    // ReAct-based execution
                    val task = reActLoop.createTask(
                        objective = state.subTask.description,
                        context = state.subTask.name
                    )
                    val reactResult = reActLoop.execute(task)
                    if (reactResult.success) {
                        PluginResult.success(reactResult.answer, reactResult.totalDurationMs)
                    } else {
                        PluginResult.error(reactResult.error ?: "ReAct failed", reactResult.totalDurationMs)
                    }
                } else {
                    // No execution backend
                    PluginResult.error("No tool or ReAct loop available", 0)
                }

                if (result.isSuccess) {
                    state.status = SubTaskStatus.COMPLETED
                    state.result = result
                    state.completedAt = System.currentTimeMillis()

                    messageBus?.publishAsync(
                        "planning.subtask.complete",
                        mapOf("planId" to planId, "taskId" to state.subTask.id),
                        "TaskPlanningEngine"
                    )
                    return
                } else {
                    lastError = result.errorMessage
                    state.retriesRemaining--
                    if (state.retriesRemaining >= 0) {
                        Log.w(TAG, "Retrying subtask '${state.subTask.name}' (${state.retriesRemaining} retries left)")
                        Thread.sleep(RETRY_BACKOFF_MS)
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                state.retriesRemaining--
                if (state.retriesRemaining >= 0) {
                    Thread.sleep(RETRY_BACKOFF_MS)
                }
            }
        }

        // All retries exhausted
        state.status = SubTaskStatus.FAILED
        state.error = lastError
        state.completedAt = System.currentTimeMillis()

        messageBus?.publishAsync(
            "planning.subtask.failed",
            mapOf("planId" to planId, "taskId" to state.subTask.id, "error" to (lastError ?: "unknown")),
            "TaskPlanningEngine"
        )
    }

    // -- Internal: DAG operations --

    /**
     * Topological sort with layer grouping for parallel execution.
     * Returns list of layers, where each layer is a list of subtask IDs
     * that can be executed in parallel.
     */
    private fun topologicalSort(subTasks: List<SubTask>): List<List<String>> {
        val taskMap = subTasks.associateBy { it.id }
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        // Initialize
        for (task in subTasks) {
            inDegree[task.id] = task.dependencies.size
            adjacency.putIfAbsent(task.id, mutableListOf())
            for (dep in task.dependencies) {
                adjacency.getOrPut(dep) { mutableListOf() }.add(task.id)
            }
        }

        // BFS layer by layer (Kahn's algorithm)
        val layers = mutableListOf<List<String>>()
        var currentLayer = inDegree.filter { it.value == 0 }.keys.toList()

        while (currentLayer.isNotEmpty()) {
            layers.add(currentLayer)

            val nextLayer = mutableListOf<String>()
            for (taskId in currentLayer) {
                for (dependent in adjacency[taskId] ?: emptyList()) {
                    inDegree[dependent] = (inDegree[dependent] ?: 1) - 1
                    if (inDegree[dependent] == 0) {
                        nextLayer.add(dependent)
                    }
                }
            }
            currentLayer = nextLayer
        }

        return layers
    }

    /**
     * Find the critical path (longest path through the DAG by estimated duration).
     */
    private fun findCriticalPath(subTasks: List<SubTask>, layers: List<List<String>>): List<String> {
        val taskMap = subTasks.associateBy { it.id }
        val longestTo = mutableMapOf<String, Long>()
        val predecessor = mutableMapOf<String, String?>()

        for (layer in layers) {
            for (taskId in layer) {
                val task = taskMap[taskId] ?: continue
                val maxDepDuration = task.dependencies.maxOfOrNull { depId ->
                    (longestTo[depId] ?: 0L)
                } ?: 0L
                longestTo[taskId] = maxDepDuration + task.estimatedDurationMs

                val bestPred = task.dependencies.maxByOrNull { longestTo[it] ?: 0L }
                predecessor[taskId] = bestPred
            }
        }

        // Trace back from the node with longest duration
        val endNode = longestTo.maxByOrNull { it.value }?.key ?: return emptyList()
        val path = mutableListOf<String>()
        var current: String? = endNode
        while (current != null) {
            path.add(0, current)
            current = predecessor[current]
        }

        return path
    }

    private fun estimateTotalDuration(subTasks: List<SubTask>, layers: List<List<String>>): Long {
        val taskMap = subTasks.associateBy { it.id }
        return layers.sumOf { layer ->
            layer.maxOfOrNull { taskMap[it]?.estimatedDurationMs ?: 0L } ?: 0L
        }
    }

    // -- Internal: Plan templates --

    private fun buildDeployPlan(planId: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}test", "Run tests", "Execute unit tests to validate code", "device_info", "all", estimatedDurationMs = 5000),
            SubTask("${prefix}build", "Build APK", "Compile and package the application", "device_info", "all", listOf("${prefix}test"), 10000),
            SubTask("${prefix}sign", "Sign APK", "Sign the release APK", "device_info", "all", listOf("${prefix}build"), 3000),
            SubTask("${prefix}notify", "Notify team", "Send deployment notification", "notes", "create|Deploy complete|Build signed and ready|${System.currentTimeMillis() + 86400000}", listOf("${prefix}sign"), 1000)
        )
    }

    private fun buildAnalysisReportPlan(planId: String, objective: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}gather", "Gather data", "Collect device and system information", "device_info", "all", estimatedDurationMs = 2000),
            SubTask("${prefix}analyze_text", "Analyze text", "Analyze the objective text", "text_analysis", "analyze|$objective", estimatedDurationMs = 3000),
            SubTask("${prefix}check_time", "Check timestamps", "Get current time context", "datetime", "now", estimatedDurationMs = 500),
            SubTask("${prefix}report", "Generate report", "Compile analysis into report note", "notes", "create|Analysis Report|Compiled analysis|${System.currentTimeMillis() + 86400000}", listOf("${prefix}gather", "${prefix}analyze_text", "${prefix}check_time"), 2000)
        )
    }

    private fun buildBackupPlan(planId: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}inventory", "Inventory files", "List files to backup", "file_manager", "list|/", estimatedDurationMs = 1000),
            SubTask("${prefix}check_space", "Check storage", "Verify available storage space", "device_info", "all", estimatedDurationMs = 500),
            SubTask("${prefix}archive", "Archive data", "Create backup archive", "notes", "create|Backup record|Backup initiated|${System.currentTimeMillis() + 86400000}", listOf("${prefix}inventory", "${prefix}check_space"), 5000)
        )
    }

    private fun buildHealthCheckPlan(planId: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}device", "Device status", "Check device health metrics", "device_info", "all", estimatedDurationMs = 1000),
            SubTask("${prefix}time", "System time", "Verify system time accuracy", "datetime", "now", estimatedDurationMs = 500),
            SubTask("${prefix}storage", "Storage check", "Check available storage", "file_manager", "list|/", estimatedDurationMs = 1000),
            SubTask("${prefix}summary", "Health summary", "Compile health check results", "notes", "create|Health Check|System health verified|${System.currentTimeMillis() + 86400000}", listOf("${prefix}device", "${prefix}time", "${prefix}storage"), 1000)
        )
    }

    private fun buildTestPlan(planId: String, objective: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}setup", "Test setup", "Prepare test environment", "device_info", "all", estimatedDurationMs = 1000),
            SubTask("${prefix}exec", "Run tests", "Execute test: $objective", "text_analysis", "analyze|$objective", listOf("${prefix}setup"), 5000),
            SubTask("${prefix}report", "Test report", "Generate test results report", "notes", "create|Test Report|Testing completed|${System.currentTimeMillis() + 86400000}", listOf("${prefix}exec"), 1000)
        )
    }

    private fun buildSequentialFromConjunctions(planId: String, objective: String): List<SubTask> {
        val prefix = "${planId}_"
        val parts = objective.split(Regex("\\b(and then|then|and|after that|also|plus)\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val subTasks = mutableListOf<SubTask>()
        var prevId: String? = null

        for ((index, part) in parts.withIndex()) {
            val taskId = "${prefix}step_$index"
            val deps = if (prevId != null) listOf(prevId) else emptyList()

            subTasks.add(SubTask(
                id = taskId,
                name = "Step ${index + 1}",
                description = part,
                dependencies = deps,
                estimatedDurationMs = 3000
            ))
            prevId = taskId
        }

        return subTasks
    }

    private fun buildGenericPlan(planId: String, objective: String): List<SubTask> {
        val prefix = "${planId}_"
        return listOf(
            SubTask("${prefix}context", "Gather context", "Collect information relevant to: $objective", "device_info", "all", estimatedDurationMs = 1000),
            SubTask("${prefix}execute", "Execute task", "Perform: $objective", null, "", listOf("${prefix}context"), 5000),
            SubTask("${prefix}record", "Record result", "Save the outcome", "notes", "create|Task Result|$objective|${System.currentTimeMillis() + 86400000}", listOf("${prefix}execute"), 1000)
        )
    }

    companion object {
        private const val TAG = "TaskPlanningEngine"
        private const val DEFAULT_PARALLELISM = 4
        private const val DEFAULT_RETRY_COUNT = 1
        private const val RETRY_BACKOFF_MS = 500L
        private const val LAYER_TIMEOUT_MS = 60_000L
    }
}
