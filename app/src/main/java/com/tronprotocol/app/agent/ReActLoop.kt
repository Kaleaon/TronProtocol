package com.tronprotocol.app.agent

import android.util.Log
import com.tronprotocol.app.bus.MessageBus
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginResult
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ReAct Loop - NanoBot-inspired reason-act-observe agent loop.
 *
 * Implements the ReAct (Reasoning + Acting) paradigm:
 *
 * 1. **THINK** - Analyze the task, retrieve context from RAG, reason about what to do
 * 2. **ACT** - Execute an action via a plugin or internal tool
 * 3. **OBSERVE** - Interpret the result, decide if task is complete or needs more steps
 *
 * Inspired by NanoBot's agent loop with:
 * - Configurable iteration limits (prevents runaway loops)
 * - Full thought chain tracking (observable reasoning)
 * - RAG-augmented reasoning (retrieves relevant memories)
 * - Plugin-based action execution
 * - MessageBus integration for observation broadcast
 * - Automatic retry with alternative actions on failure
 * - Budget tracking (token-like cost accounting)
 */
class ReActLoop(
    private val pluginManager: PluginManager,
    private val ragStore: RAGStore? = null,
    private val messageBus: MessageBus? = null,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val maxBudget: Int = DEFAULT_MAX_BUDGET
) {

    /** A single step in the ReAct chain. */
    data class Step(
        val iteration: Int,
        val phase: Phase,
        val content: String,
        val toolUsed: String? = null,
        val toolInput: String? = null,
        val toolOutput: String? = null,
        val durationMs: Long = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Phase { THINK, ACT, OBSERVE }

    /** The final result of a ReAct execution. */
    data class ReActResult(
        val taskId: String,
        val success: Boolean,
        val answer: String,
        val steps: List<Step>,
        val totalIterations: Int,
        val totalDurationMs: Long,
        val budgetUsed: Int,
        val error: String? = null
    )

    /** Task definition for the ReAct loop. */
    data class Task(
        val id: String,
        val objective: String,
        val context: String = "",
        val availableTools: List<String> = emptyList(),
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS
    )

    /** Tool selection result from the THINK phase. */
    private data class ToolSelection(
        val toolId: String,
        val input: String,
        val reasoning: String
    )

    // Active tasks
    private val activeTasks = ConcurrentHashMap<String, Boolean>()

    // Stats
    private val totalTasks = AtomicLong(0)
    private val successfulTasks = AtomicLong(0)
    private val failedTasks = AtomicLong(0)
    private val totalSteps = AtomicLong(0)
    private val taskIdCounter = AtomicInteger(0)

    /**
     * Execute a task using the ReAct loop.
     */
    fun execute(task: Task): ReActResult {
        val startTime = System.currentTimeMillis()
        val steps = mutableListOf<Step>()
        var budgetUsed = 0
        val effectiveMaxIter = minOf(task.maxIterations, maxIterations)

        totalTasks.incrementAndGet()
        activeTasks[task.id] = true

        messageBus?.publishAsync("agent.react.start", task, "ReActLoop")

        try {
            // Determine which tools are available
            val availableTools = if (task.availableTools.isNotEmpty()) {
                task.availableTools
            } else {
                pluginManager.getEnabledPlugins().map { it.id }
            }

            var lastObservation = ""
            var isComplete = false
            var finalAnswer = ""
            var iteration = 0

            while (iteration < effectiveMaxIter && !isComplete && budgetUsed < maxBudget) {
                iteration++

                // --- THINK PHASE ---
                val thinkStart = System.currentTimeMillis()
                val thinkResult = think(task, lastObservation, steps, availableTools, iteration)
                val thinkStep = Step(
                    iteration = iteration,
                    phase = Phase.THINK,
                    content = thinkResult.reasoning,
                    durationMs = System.currentTimeMillis() - thinkStart
                )
                steps.add(thinkStep)
                budgetUsed += estimateCost(thinkResult.reasoning)

                messageBus?.publishAsync("agent.react.think", thinkStep, "ReActLoop")

                // Check if thinking concludes the task
                if (thinkResult.toolId == TOOL_FINISH) {
                    finalAnswer = thinkResult.input
                    isComplete = true

                    val observeStep = Step(
                        iteration = iteration,
                        phase = Phase.OBSERVE,
                        content = "Task completed. Final answer: $finalAnswer"
                    )
                    steps.add(observeStep)
                    break
                }

                // --- ACT PHASE ---
                val actStart = System.currentTimeMillis()
                val actResult = act(thinkResult.toolId, thinkResult.input)
                val actStep = Step(
                    iteration = iteration,
                    phase = Phase.ACT,
                    content = if (actResult.isSuccess) "Action succeeded" else "Action failed: ${actResult.errorMessage}",
                    toolUsed = thinkResult.toolId,
                    toolInput = thinkResult.input,
                    toolOutput = actResult.data ?: actResult.errorMessage,
                    durationMs = System.currentTimeMillis() - actStart
                )
                steps.add(actStep)
                budgetUsed += estimateCost(thinkResult.input) + estimateCost(actResult.data ?: "")

                messageBus?.publishAsync("agent.react.act", actStep, "ReActLoop")

                // --- OBSERVE PHASE ---
                val observeStart = System.currentTimeMillis()
                val observation = observe(task, actResult, thinkResult, steps, iteration, effectiveMaxIter)
                val observeStep = Step(
                    iteration = iteration,
                    phase = Phase.OBSERVE,
                    content = observation,
                    durationMs = System.currentTimeMillis() - observeStart
                )
                steps.add(observeStep)
                budgetUsed += estimateCost(observation)
                lastObservation = observation

                messageBus?.publishAsync("agent.react.observe", observeStep, "ReActLoop")

                // Store observation in RAG for future reference
                ragStore?.addMemory(
                    "ReAct[${task.id}] iter=$iteration tool=${thinkResult.toolId}: $observation",
                    0.6f
                )

                totalSteps.incrementAndGet()
            }

            val totalDuration = System.currentTimeMillis() - startTime

            if (!isComplete) {
                finalAnswer = buildFinalAnswer(steps, lastObservation)
            }

            val success = isComplete || lastObservation.contains("success", ignoreCase = true)
            if (success) successfulTasks.incrementAndGet() else failedTasks.incrementAndGet()

            val result = ReActResult(
                taskId = task.id,
                success = success,
                answer = finalAnswer,
                steps = steps,
                totalIterations = iteration,
                totalDurationMs = totalDuration,
                budgetUsed = budgetUsed,
                error = if (!success) "Max iterations reached without resolution" else null
            )

            messageBus?.publishAsync("agent.react.complete", result, "ReActLoop")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "ReAct loop failed for task ${task.id}", e)
            failedTasks.incrementAndGet()
            return ReActResult(
                taskId = task.id,
                success = false,
                answer = "",
                steps = steps,
                totalIterations = steps.size / 3,
                totalDurationMs = System.currentTimeMillis() - startTime,
                budgetUsed = budgetUsed,
                error = "ReAct loop failed: ${e.message}"
            )
        } finally {
            activeTasks.remove(task.id)
        }
    }

    /**
     * Create a task and generate a unique ID.
     */
    fun createTask(objective: String, context: String = "", availableTools: List<String> = emptyList()): Task {
        return Task(
            id = "react_${taskIdCounter.incrementAndGet()}_${System.currentTimeMillis()}",
            objective = objective,
            context = context,
            availableTools = availableTools
        )
    }

    /**
     * Get execution statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_tasks" to totalTasks.get(),
        "successful_tasks" to successfulTasks.get(),
        "failed_tasks" to failedTasks.get(),
        "total_steps" to totalSteps.get(),
        "active_tasks" to activeTasks.size,
        "success_rate" to if (totalTasks.get() > 0) {
            successfulTasks.get().toFloat() / totalTasks.get()
        } else 0f,
        "avg_steps_per_task" to if (totalTasks.get() > 0) {
            totalSteps.get().toFloat() / totalTasks.get()
        } else 0f
    )

    // -- Internal: THINK phase --

    private fun think(
        task: Task,
        lastObservation: String,
        previousSteps: List<Step>,
        availableTools: List<String>,
        iteration: Int
    ): ToolSelection {
        // Retrieve relevant context from RAG
        val ragContext = ragStore?.let { store ->
            val results = store.retrieve(task.objective, RetrievalStrategy.MEMRL, 3)
            results.joinToString("\n") { "- ${it.chunk.content.take(150)}" }
        } ?: ""

        // Build reasoning chain
        val reasoning = StringBuilder()
        reasoning.append("Iteration $iteration for task: ${task.objective}\n")

        if (ragContext.isNotEmpty()) {
            reasoning.append("Relevant memories:\n$ragContext\n")
        }

        if (lastObservation.isNotEmpty()) {
            reasoning.append("Last observation: $lastObservation\n")
        }

        // Select the best tool based on task analysis
        val selectedTool = selectTool(task.objective, lastObservation, availableTools, iteration, previousSteps)

        reasoning.append("Selected tool: ${selectedTool.toolId} because: ${selectedTool.reasoning}")

        return ToolSelection(
            toolId = selectedTool.toolId,
            input = selectedTool.input,
            reasoning = reasoning.toString()
        )
    }

    /**
     * Heuristic tool selection based on keyword analysis of the task.
     * In a full LLM-backed system, this would be an LLM call.
     */
    private fun selectTool(
        objective: String,
        lastObservation: String,
        availableTools: List<String>,
        iteration: Int,
        previousSteps: List<Step>
    ): ToolSelection {
        val objectiveLower = objective.lowercase()
        val previousTools = previousSteps
            .filter { it.phase == Phase.ACT }
            .mapNotNull { it.toolUsed }

        // If we've had successful observations and are past iteration 1, check if done
        if (iteration > 1 && lastObservation.contains("result", ignoreCase = true)) {
            val failedActions = previousSteps.count {
                it.phase == Phase.ACT && it.content.startsWith("Action failed")
            }
            val successActions = previousSteps.count {
                it.phase == Phase.ACT && it.content.startsWith("Action succeeded")
            }

            if (successActions > 0 && successActions > failedActions) {
                return ToolSelection(
                    TOOL_FINISH,
                    "Task completed based on observations: $lastObservation",
                    "Sufficient successful actions observed"
                )
            }
        }

        // Match objective keywords to available tools
        val toolScores = mutableMapOf<String, Float>()

        for (tool in availableTools) {
            var score = 0f

            // Keyword matching
            when {
                tool == "calculator" && objectiveLower.matches(Regex(".*\\b(calculate|math|compute|sum|add|multiply|divide)\\b.*")) -> score += 3f
                tool == "web_search" && objectiveLower.matches(Regex(".*\\b(search|find|look up|google|web)\\b.*")) -> score += 3f
                tool == "datetime" && objectiveLower.matches(Regex(".*\\b(time|date|today|now|schedule|clock)\\b.*")) -> score += 3f
                tool == "text_analysis" && objectiveLower.matches(Regex(".*\\b(analyze|sentiment|summarize|text|language)\\b.*")) -> score += 3f
                tool == "notes" && objectiveLower.matches(Regex(".*\\b(note|write|save|record|remember)\\b.*")) -> score += 3f
                tool == "file_manager" && objectiveLower.matches(Regex(".*\\b(file|read|write|directory|folder|list)\\b.*")) -> score += 3f
                tool == "device_info" && objectiveLower.matches(Regex(".*\\b(device|battery|storage|system|info|status)\\b.*")) -> score += 3f
                tool == "task_automation" && objectiveLower.matches(Regex(".*\\b(task|automate|schedule|workflow|plan)\\b.*")) -> score += 3f
                tool == "sandbox_exec" && objectiveLower.matches(Regex(".*\\b(code|execute|run|script|program)\\b.*")) -> score += 3f
                tool == "personalization" && objectiveLower.matches(Regex(".*\\b(preference|personalize|setting|customize)\\b.*")) -> score += 3f
            }

            // Penalty for recently used tools (encourage diversity)
            if (tool in previousTools) {
                score -= 1f
            }

            // Small bonus for tools not yet tried
            if (tool !in previousTools && score == 0f) {
                score += 0.1f
            }

            toolScores[tool] = score
        }

        // Select highest-scoring tool
        val best = toolScores.maxByOrNull { it.value }
        if (best != null && best.value > 0f) {
            return ToolSelection(
                toolId = best.key,
                input = buildToolInput(best.key, objective),
                reasoning = "Best match for objective (score=${best.value})"
            )
        }

        // Fallback: try the first available tool not yet used
        val untried = availableTools.filter { it !in previousTools }
        if (untried.isNotEmpty()) {
            return ToolSelection(
                toolId = untried.first(),
                input = objective,
                reasoning = "Fallback to untried tool"
            )
        }

        // Nothing left to try
        return ToolSelection(
            TOOL_FINISH,
            "Unable to determine appropriate tool for: $objective",
            "No suitable tools remaining"
        )
    }

    private fun buildToolInput(toolId: String, objective: String): String {
        return when (toolId) {
            "calculator" -> {
                // Try to extract math expression
                val mathRegex = Regex("[0-9+\\-*/().\\s]+")
                val match = mathRegex.find(objective)
                match?.value?.trim() ?: objective
            }
            "web_search" -> objective
            "datetime" -> "now"
            "device_info" -> "all"
            "text_analysis" -> "analyze|$objective"
            "notes" -> "list"
            else -> objective
        }
    }

    // -- Internal: ACT phase --

    private fun act(toolId: String, input: String): PluginResult {
        if (toolId == TOOL_FINISH) {
            return PluginResult.success(input, 0)
        }

        return try {
            pluginManager.executePlugin(toolId, input)
        } catch (e: Exception) {
            Log.w(TAG, "Action failed for tool '$toolId'", e)
            PluginResult.error("Tool execution failed: ${e.message}", 0)
        }
    }

    // -- Internal: OBSERVE phase --

    private fun observe(
        task: Task,
        actResult: PluginResult,
        toolSelection: ToolSelection,
        previousSteps: List<Step>,
        iteration: Int,
        maxIter: Int
    ): String {
        val observation = StringBuilder()

        if (actResult.isSuccess) {
            observation.append("Tool '${toolSelection.toolId}' returned result: ${actResult.data?.take(300)}")
        } else {
            observation.append("Tool '${toolSelection.toolId}' failed: ${actResult.errorMessage}")
        }

        // Assess progress
        val totalActs = previousSteps.count { it.phase == Phase.ACT }
        val successActs = previousSteps.count { it.phase == Phase.ACT && it.content.startsWith("Action succeeded") }
        observation.append("\nProgress: $successActs/${totalActs + 1} actions succeeded, iteration $iteration/$maxIter")

        return observation.toString()
    }

    private fun buildFinalAnswer(steps: List<Step>, lastObservation: String): String {
        val successfulResults = steps
            .filter { it.phase == Phase.ACT && it.content.startsWith("Action succeeded") }
            .mapNotNull { it.toolOutput }

        return if (successfulResults.isNotEmpty()) {
            "Results: ${successfulResults.joinToString("; ") { it.take(200) }}"
        } else {
            "Attempted task but could not fully resolve. Last observation: $lastObservation"
        }
    }

    private fun estimateCost(text: String): Int = text.length / 4  // ~4 chars per token

    companion object {
        private const val TAG = "ReActLoop"
        private const val TOOL_FINISH = "__finish__"
        private const val DEFAULT_MAX_ITERATIONS = 10
        private const val DEFAULT_MAX_BUDGET = 10000
    }
}
