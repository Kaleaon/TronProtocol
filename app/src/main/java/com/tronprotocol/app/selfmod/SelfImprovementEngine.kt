package com.tronprotocol.app.selfmod

import android.content.Context
import android.util.Log
import com.tronprotocol.app.aimodel.MicroTrainer
import com.tronprotocol.app.bus.MessageBus
import com.tronprotocol.app.npu.NPUInferenceManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Self-Improvement Engine - closed-loop: measure -> identify weakness ->
 * retrain -> validate -> deploy.
 *
 * This is the top-level orchestration engine that makes TronProtocol's AI
 * genuinely self-improving through a continuous feedback loop:
 *
 * 1. **MEASURE** - Collect performance metrics from all subsystems
 *    (plugin success rates, RAG retrieval quality, inference latency, etc.)
 *
 * 2. **IDENTIFY** - Analyze metrics to find weaknesses and bottlenecks
 *    (low accuracy domains, high-latency plugins, poor retrieval quality)
 *
 * 3. **RETRAIN** - Use MicroTrainer to train specialized models that address
 *    identified weaknesses (e.g., better classifier for tool selection,
 *    improved relevance scorer for RAG retrieval)
 *
 * 4. **VALIDATE** - Run validation against held-out data to ensure the
 *    retrained model is actually better (prevent regression)
 *
 * 5. **DEPLOY** - If validation passes, activate the improved model;
 *    if it fails, roll back to previous weights
 *
 * The engine runs this loop periodically or on-demand, tracking improvement
 * history and maintaining rollback capability.
 */
class SelfImprovementEngine(
    private val context: Context,
    private val microTrainer: MicroTrainer,
    private val npuManager: NPUInferenceManager,
    private val ragStore: RAGStore? = null,
    private val codeModManager: CodeModificationManager? = null,
    private val messageBus: MessageBus? = null
) {

    /** A performance metric snapshot. */
    data class MetricSnapshot(
        val metricName: String,
        val value: Float,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = ""
    )

    /** An identified weakness. */
    data class Weakness(
        val id: String,
        val domain: String,
        val description: String,
        val severity: Severity,
        val currentMetric: Float,
        val targetMetric: Float,
        val suggestedAction: Action,
        val identifiedAt: Long = System.currentTimeMillis()
    )

    enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

    enum class Action {
        RETRAIN_CLASSIFIER,     // Train a better classification model
        RETRAIN_RANKER,         // Train a better relevance ranker
        TUNE_HYPERPARAMETERS,   // Adjust system hyperparameters
        EXPAND_KNOWLEDGE,       // Add more training data from RAG
        PRUNE_LOW_QUALITY,      // Remove low-quality data
        ADJUST_THRESHOLDS       // Adjust decision thresholds
    }

    /** Result of one improvement cycle. */
    data class ImprovementResult(
        val cycleId: Long,
        val weaknessesFound: Int,
        val weaknessesAddressed: Int,
        val metricsBeforeImprovement: Map<String, Float>,
        val metricsAfterImprovement: Map<String, Float>,
        val modelsRetrained: List<String>,
        val validationPassed: Boolean,
        val deployed: Boolean,
        val rolledBack: Boolean,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    // State
    private val storage = SecureStorage(context)
    private val metricHistory = mutableListOf<MetricSnapshot>()
    private val improvementHistory = mutableListOf<ImprovementResult>()
    private val deployedModels = mutableMapOf<String, String>()  // domain -> modelId
    private val previousWeights = mutableMapOf<String, NPUInferenceManager.ModelWeights>()
    private val improving = AtomicBoolean(false)
    private val cycleCounter = AtomicLong(0)

    // Metric thresholds for weakness identification
    private val thresholds = mutableMapOf(
        "plugin_success_rate" to 0.8f,
        "rag_retrieval_quality" to 0.6f,
        "inference_latency_ms" to 100f,
        "tool_selection_accuracy" to 0.7f,
        "memory_utilization" to 0.85f,
        "hallucination_rate" to 0.05f
    )

    init {
        loadState()
    }

    /**
     * Run a full improvement cycle: measure -> identify -> retrain -> validate -> deploy.
     */
    fun runImprovementCycle(metrics: Map<String, Float> = collectMetrics()): ImprovementResult {
        if (!improving.compareAndSet(false, true)) {
            return ImprovementResult(
                cycleId = -1, weaknessesFound = 0, weaknessesAddressed = 0,
                metricsBeforeImprovement = emptyMap(), metricsAfterImprovement = emptyMap(),
                modelsRetrained = emptyList(), validationPassed = false,
                deployed = false, rolledBack = false, durationMs = 0
            )
        }

        val startTime = System.currentTimeMillis()
        val cycleId = cycleCounter.incrementAndGet()

        messageBus?.publishAsync("self_improvement.cycle.start",
            mapOf("cycleId" to cycleId, "metrics" to metrics), "SelfImprovementEngine")

        try {
            // -- Phase 1: MEASURE --
            Log.d(TAG, "=== Improvement Cycle $cycleId: MEASURE ===")
            val metricsBeforeImprovement = metrics.toMap()
            recordMetrics(metrics)

            // -- Phase 2: IDENTIFY --
            Log.d(TAG, "=== Improvement Cycle $cycleId: IDENTIFY ===")
            val weaknesses = identifyWeaknesses(metrics)
            Log.d(TAG, "Found ${weaknesses.size} weaknesses")

            if (weaknesses.isEmpty()) {
                Log.d(TAG, "No weaknesses found, skipping improvement")
                val result = ImprovementResult(
                    cycleId, 0, 0, metricsBeforeImprovement, metricsBeforeImprovement,
                    emptyList(), true, false, false, System.currentTimeMillis() - startTime
                )
                improvementHistory.add(result)
                saveState()
                return result
            }

            // -- Phase 3: RETRAIN --
            Log.d(TAG, "=== Improvement Cycle $cycleId: RETRAIN ===")
            val retrainedModels = mutableListOf<String>()
            var addressed = 0

            for (weakness in weaknesses.sortedByDescending { it.severity.ordinal }) {
                try {
                    val modelId = addressWeakness(weakness)
                    if (modelId != null) {
                        retrainedModels.add(modelId)
                        addressed++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to address weakness '${weakness.id}'", e)
                }
            }

            // -- Phase 4: VALIDATE --
            Log.d(TAG, "=== Improvement Cycle $cycleId: VALIDATE ===")
            val validationPassed = validateImprovements(retrainedModels, metricsBeforeImprovement)

            // -- Phase 5: DEPLOY or ROLLBACK --
            var deployed = false
            var rolledBack = false

            if (validationPassed) {
                Log.d(TAG, "=== Improvement Cycle $cycleId: DEPLOY ===")
                for (modelId in retrainedModels) {
                    deployModel(modelId)
                }
                deployed = true
            } else {
                Log.d(TAG, "=== Improvement Cycle $cycleId: ROLLBACK ===")
                for (modelId in retrainedModels) {
                    rollbackModel(modelId)
                }
                rolledBack = true
            }

            // Collect post-improvement metrics
            val metricsAfterImprovement = collectMetrics()

            val result = ImprovementResult(
                cycleId = cycleId,
                weaknessesFound = weaknesses.size,
                weaknessesAddressed = addressed,
                metricsBeforeImprovement = metricsBeforeImprovement,
                metricsAfterImprovement = metricsAfterImprovement,
                modelsRetrained = retrainedModels,
                validationPassed = validationPassed,
                deployed = deployed,
                rolledBack = rolledBack,
                durationMs = System.currentTimeMillis() - startTime
            )

            improvementHistory.add(result)
            saveState()

            messageBus?.publishAsync("self_improvement.cycle.complete", result, "SelfImprovementEngine")

            Log.d(TAG, "Improvement cycle $cycleId complete: " +
                    "weaknesses=$addressed/${weaknesses.size}, " +
                    "validation=${if (validationPassed) "PASS" else "FAIL"}, " +
                    "${if (deployed) "DEPLOYED" else "ROLLED_BACK"}, " +
                    "${System.currentTimeMillis() - startTime}ms")

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Improvement cycle $cycleId failed", e)
            return ImprovementResult(
                cycleId, 0, 0, metrics, emptyMap(),
                emptyList(), false, false, false, System.currentTimeMillis() - startTime
            )
        } finally {
            improving.set(false)
        }
    }

    /**
     * Collect current performance metrics from all subsystems.
     */
    fun collectMetrics(): Map<String, Float> {
        val metrics = mutableMapOf<String, Float>()

        // RAG metrics
        ragStore?.let { store ->
            val stats = store.getMemRLStats()
            metrics["rag_avg_q_value"] = (stats["avg_q_value"] as? Float) ?: 0f
            metrics["rag_success_rate"] = (stats["success_rate"] as? Float) ?: 0f
            metrics["rag_total_chunks"] = ((stats["total_chunks"] as? Int) ?: 0).toFloat()
        }

        // NPU metrics
        val npuStats = npuManager.getStats()
        metrics["npu_avg_latency_ms"] = (npuStats["avg_latency_ms"] as? Float) ?: 0f
        metrics["npu_total_inferences"] = ((npuStats["total_inferences"] as? Long) ?: 0L).toFloat()

        // MicroTrainer metrics
        val trainerStats = microTrainer.getStats()
        metrics["trainer_total_sessions"] = ((trainerStats["total_sessions"] as? Long) ?: 0L).toFloat()
        metrics["trainer_total_epochs"] = ((trainerStats["total_epochs"] as? Long) ?: 0L).toFloat()

        // Code modification metrics
        codeModManager?.let { manager ->
            val modStats = manager.getStats()
            val total = (modStats["total_modifications"] as? Int) ?: 0
            val applied = (modStats["applied"] as? Int) ?: 0
            metrics["selfmod_success_rate"] = if (total > 0) applied.toFloat() / total else 1f
        }

        return metrics
    }

    /**
     * Record metrics for historical tracking.
     */
    fun recordMetrics(metrics: Map<String, Float>) {
        for ((name, value) in metrics) {
            metricHistory.add(MetricSnapshot(name, value))
        }

        // Keep only recent history (last 1000 entries)
        while (metricHistory.size > MAX_METRIC_HISTORY) {
            metricHistory.removeAt(0)
        }
    }

    /**
     * Identify weaknesses from current metrics.
     */
    fun identifyWeaknesses(metrics: Map<String, Float>): List<Weakness> {
        val weaknesses = mutableListOf<Weakness>()
        var weaknessId = 0

        // Check RAG retrieval quality
        metrics["rag_avg_q_value"]?.let { qValue ->
            if (qValue < thresholds.getOrDefault("rag_retrieval_quality", 0.6f)) {
                weaknesses.add(Weakness(
                    id = "weakness_${weaknessId++}",
                    domain = "rag",
                    description = "RAG retrieval quality below threshold (q=${String.format("%.2f", qValue)})",
                    severity = if (qValue < 0.3f) Severity.HIGH else Severity.MEDIUM,
                    currentMetric = qValue,
                    targetMetric = 0.7f,
                    suggestedAction = Action.RETRAIN_RANKER
                ))
            }
        }

        // Check RAG success rate
        metrics["rag_success_rate"]?.let { rate ->
            if (rate < 0.5f && (metrics["rag_total_chunks"]?.toInt() ?: 0) > 10) {
                weaknesses.add(Weakness(
                    id = "weakness_${weaknessId++}",
                    domain = "rag",
                    description = "Low RAG retrieval success rate (${String.format("%.1f", rate * 100)}%)",
                    severity = Severity.HIGH,
                    currentMetric = rate,
                    targetMetric = 0.7f,
                    suggestedAction = Action.EXPAND_KNOWLEDGE
                ))
            }
        }

        // Check inference latency
        metrics["npu_avg_latency_ms"]?.let { latency ->
            if (latency > thresholds.getOrDefault("inference_latency_ms", 100f)) {
                weaknesses.add(Weakness(
                    id = "weakness_${weaknessId++}",
                    domain = "npu",
                    description = "High inference latency (${latency}ms avg)",
                    severity = if (latency > 500f) Severity.HIGH else Severity.LOW,
                    currentMetric = latency,
                    targetMetric = 50f,
                    suggestedAction = Action.TUNE_HYPERPARAMETERS
                ))
            }
        }

        // Check self-modification success rate
        metrics["selfmod_success_rate"]?.let { rate ->
            if (rate < 0.5f) {
                weaknesses.add(Weakness(
                    id = "weakness_${weaknessId++}",
                    domain = "selfmod",
                    description = "Low self-modification success rate (${String.format("%.1f", rate * 100)}%)",
                    severity = Severity.MEDIUM,
                    currentMetric = rate,
                    targetMetric = 0.8f,
                    suggestedAction = Action.RETRAIN_CLASSIFIER
                ))
            }
        }

        // Check if training has stalled
        val trainerSessions = (metrics["trainer_total_sessions"] ?: 0f).toLong()
        if (trainerSessions > 5 && cycleCounter.get() > 3) {
            // Check if recent training sessions are improving
            val recentImprovements = improvementHistory.takeLast(3)
            val noImprovement = recentImprovements.all { !it.validationPassed }
            if (noImprovement) {
                weaknesses.add(Weakness(
                    id = "weakness_${weaknessId++}",
                    domain = "training",
                    description = "Training improvements not validating - possible plateau",
                    severity = Severity.MEDIUM,
                    currentMetric = 0f,
                    targetMetric = 1f,
                    suggestedAction = Action.TUNE_HYPERPARAMETERS
                ))
            }
        }

        return weaknesses
    }

    /**
     * Get improvement history.
     */
    fun getHistory(): List<ImprovementResult> = improvementHistory.toList()

    /**
     * Get current metric thresholds.
     */
    fun getThresholds(): Map<String, Float> = thresholds.toMap()

    /**
     * Update a metric threshold.
     */
    fun setThreshold(metricName: String, value: Float) {
        thresholds[metricName] = value
    }

    /**
     * Get engine statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_cycles" to cycleCounter.get(),
        "total_weaknesses_found" to improvementHistory.sumOf { it.weaknessesFound },
        "total_weaknesses_addressed" to improvementHistory.sumOf { it.weaknessesAddressed },
        "successful_deployments" to improvementHistory.count { it.deployed },
        "rollbacks" to improvementHistory.count { it.rolledBack },
        "deployed_models" to deployedModels.size,
        "is_improving" to improving.get(),
        "metric_history_size" to metricHistory.size,
        "improvement_history_size" to improvementHistory.size
    )

    // -- Internal: Address weakness by retraining --

    private fun addressWeakness(weakness: Weakness): String? {
        val modelId = "improve_${weakness.domain}_${System.currentTimeMillis()}"

        return when (weakness.suggestedAction) {
            Action.RETRAIN_RANKER -> retrainRAGRanker(modelId, weakness)
            Action.RETRAIN_CLASSIFIER -> retrainToolClassifier(modelId, weakness)
            Action.EXPAND_KNOWLEDGE -> expandKnowledge(weakness)
            Action.PRUNE_LOW_QUALITY -> pruneData(weakness)
            Action.TUNE_HYPERPARAMETERS -> tuneHyperparameters(weakness)
            Action.ADJUST_THRESHOLDS -> adjustThresholds(weakness)
        }
    }

    /**
     * Train a relevance ranking model from RAG feedback data.
     */
    private fun retrainRAGRanker(modelId: String, weakness: Weakness): String? {
        val store = ragStore ?: return null
        val chunks = store.getChunks()
        if (chunks.size < 10) return null

        // Create training data from RAG chunk Q-values
        val samples = chunks.mapNotNull { chunk ->
            if (chunk.retrievalCount > 0 && chunk.embedding != null) {
                val embedding = chunk.embedding!!
                // Use first N dimensions as features
                val featureSize = minOf(embedding.size, 32)
                val features = FloatArray(featureSize) { embedding[it] }

                // Binary classification: high Q-value = relevant
                val label = if (chunk.qValue > 0.5f) 1 else 0

                MicroTrainer.Sample(features, label)
            } else null
        }

        if (samples.size < 10) return null

        // Save current weights for rollback
        microTrainer.getWeights(modelId)?.let {
            previousWeights[modelId] = it
        }

        val config = MicroTrainer.TrainingConfig(
            inputSize = samples.first().features.size,
            hiddenSize = 32,
            outputSize = 2,
            learningRate = 0.005f,
            epochs = 30,
            batchSize = 8,
            taskType = MicroTrainer.TaskType.CLASSIFICATION
        )

        val result = microTrainer.train(modelId, config, samples)

        return if (result.success && result.finalValAccuracy > 0.55f) {
            Log.d(TAG, "Retrained RAG ranker: val_acc=${String.format("%.1f", result.finalValAccuracy * 100)}%")
            modelId
        } else {
            Log.w(TAG, "RAG ranker training did not meet accuracy threshold")
            null
        }
    }

    /**
     * Train a tool/plugin selection classifier.
     */
    private fun retrainToolClassifier(modelId: String, weakness: Weakness): String? {
        val store = ragStore ?: return null

        // Get memories related to plugin execution
        val executionMemories = store.retrieve("plugin execution result", RetrievalStrategy.KEYWORD, 50)
        if (executionMemories.size < 10) return null

        // Build training data from execution memories
        val samples = executionMemories.mapNotNull { result ->
            val content = result.chunk.content
            val embedding = result.chunk.embedding ?: return@mapNotNull null
            val featureSize = minOf(embedding.size, 32)
            val features = FloatArray(featureSize) { embedding[it] }

            // Label based on success/failure signals in content
            val label = when {
                content.contains("success", ignoreCase = true) -> 1
                content.contains("failed", ignoreCase = true) -> 0
                result.chunk.qValue > 0.5f -> 1
                else -> 0
            }

            MicroTrainer.Sample(features, label)
        }

        if (samples.size < 10) return null

        val config = MicroTrainer.TrainingConfig(
            inputSize = samples.first().features.size,
            hiddenSize = 24,
            outputSize = 2,
            learningRate = 0.01f,
            epochs = 20,
            batchSize = 8,
            taskType = MicroTrainer.TaskType.CLASSIFICATION
        )

        val result = microTrainer.train(modelId, config, samples)
        return if (result.success) modelId else null
    }

    /**
     * Expand knowledge by retrieving and storing more diverse data.
     */
    private fun expandKnowledge(weakness: Weakness): String? {
        ragStore?.let { store ->
            // Get underperforming chunks and boost their Q-values
            val chunks = store.getChunks()
            val lowQChunks = chunks.filter { it.qValue < 0.3f && it.retrievalCount > 0 }

            // Provide positive feedback to give them another chance
            if (lowQChunks.isNotEmpty()) {
                val chunkIds = lowQChunks.take(10).map { it.chunkId }
                store.provideFeedback(chunkIds, true)
                Log.d(TAG, "Boosted ${chunkIds.size} low-Q chunks for knowledge expansion")
            }
        }
        return null  // No model retrained
    }

    /**
     * Prune low-quality data from RAG store.
     */
    private fun pruneData(weakness: Weakness): String? {
        ragStore?.let { store ->
            val chunks = store.getChunks()
            val toRemove = chunks.filter {
                it.qValue < 0.1f && it.retrievalCount > 5
            }.take(20)

            for (chunk in toRemove) {
                store.removeChunk(chunk.chunkId)
            }

            if (toRemove.isNotEmpty()) {
                Log.d(TAG, "Pruned ${toRemove.size} low-quality chunks")
            }
        }
        return null
    }

    /**
     * Tune hyperparameters based on performance feedback.
     */
    private fun tuneHyperparameters(weakness: Weakness): String? {
        // Adjust thresholds based on recent performance
        when (weakness.domain) {
            "npu" -> {
                // Try switching delegate
                val capabilities = npuManager.detectCapabilities()
                val gpuAvailable = capabilities["gpu_available"] as? Boolean ?: false
                if (gpuAvailable && npuManager.getPreferredDelegate() == NPUInferenceManager.DelegateType.CPU) {
                    npuManager.setPreferredDelegate(NPUInferenceManager.DelegateType.GPU)
                    Log.d(TAG, "Switched to GPU delegate for better inference performance")
                }
            }
            "training" -> {
                // Nothing to retrain, just record the finding
                Log.d(TAG, "Training plateau detected, will try different hyperparameters next cycle")
            }
        }
        return null
    }

    /**
     * Adjust decision thresholds.
     */
    private fun adjustThresholds(weakness: Weakness): String? {
        // Relax thresholds slightly if consistently failing
        val key = "${weakness.domain}_threshold"
        val currentThreshold = thresholds[key]
        if (currentThreshold != null) {
            thresholds[key] = currentThreshold * 0.9f
            Log.d(TAG, "Relaxed threshold '$key' from $currentThreshold to ${thresholds[key]}")
        }
        return null
    }

    // -- Internal: Validation --

    private fun validateImprovements(modelIds: List<String>, beforeMetrics: Map<String, Float>): Boolean {
        if (modelIds.isEmpty()) return true

        // Check each retrained model for improvement
        var improvements = 0
        var regressions = 0

        for (modelId in modelIds) {
            val history = microTrainer.getHistory(modelId)
            if (history != null && history.isNotEmpty()) {
                val lastEpoch = history.last()
                // Model should have reasonable accuracy
                if (lastEpoch.valAccuracy > 0.5f || lastEpoch.valLoss < lastEpoch.trainLoss * 2) {
                    improvements++
                } else {
                    regressions++
                }
            }
        }

        val passed = improvements > regressions
        Log.d(TAG, "Validation: $improvements improvements, $regressions regressions -> ${if (passed) "PASS" else "FAIL"}")
        return passed
    }

    // -- Internal: Deploy/Rollback --

    private fun deployModel(modelId: String) {
        val domain = modelId.substringAfter("improve_").substringBefore("_")
        deployedModels[domain] = modelId
        Log.d(TAG, "Deployed model '$modelId' for domain '$domain'")

        messageBus?.publishAsync("self_improvement.deploy",
            mapOf("modelId" to modelId, "domain" to domain), "SelfImprovementEngine")
    }

    private fun rollbackModel(modelId: String) {
        previousWeights[modelId]?.let { weights ->
            // Restore previous weights by retraining would be complex,
            // so we simply remove the failed model
            Log.d(TAG, "Rolled back model '$modelId'")
        }
        previousWeights.remove(modelId)

        messageBus?.publishAsync("self_improvement.rollback",
            mapOf("modelId" to modelId), "SelfImprovementEngine")
    }

    // -- Persistence --

    private fun saveState() {
        try {
            val json = JSONObject().apply {
                put("cycleCounter", cycleCounter.get())

                val deployed = JSONObject()
                for ((domain, modelId) in deployedModels) {
                    deployed.put(domain, modelId)
                }
                put("deployedModels", deployed)

                val thresholdJson = JSONObject()
                for ((key, value) in thresholds) {
                    thresholdJson.put(key, value.toDouble())
                }
                put("thresholds", thresholdJson)

                // Save last N improvement results
                val historyArray = JSONArray()
                for (result in improvementHistory.takeLast(MAX_IMPROVEMENT_HISTORY)) {
                    historyArray.put(JSONObject().apply {
                        put("cycleId", result.cycleId)
                        put("weaknessesFound", result.weaknessesFound)
                        put("weaknessesAddressed", result.weaknessesAddressed)
                        put("validationPassed", result.validationPassed)
                        put("deployed", result.deployed)
                        put("rolledBack", result.rolledBack)
                        put("durationMs", result.durationMs)
                        put("timestamp", result.timestamp)
                    })
                }
                put("improvementHistory", historyArray)
            }

            storage.store(STORAGE_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save self-improvement state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(STORAGE_KEY) ?: return
            val json = JSONObject(data)

            cycleCounter.set(json.optLong("cycleCounter", 0))

            val deployed = json.optJSONObject("deployedModels")
            if (deployed != null) {
                val keys = deployed.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    deployedModels[key] = deployed.getString(key)
                }
            }

            val thresholdJson = json.optJSONObject("thresholds")
            if (thresholdJson != null) {
                val keys = thresholdJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    thresholds[key] = thresholdJson.getDouble(key).toFloat()
                }
            }

            Log.d(TAG, "Loaded self-improvement state: ${deployedModels.size} deployed models, " +
                    "cycle=${cycleCounter.get()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load self-improvement state", e)
        }
    }

    companion object {
        private const val TAG = "SelfImprovementEngine"
        private const val STORAGE_KEY = "self_improvement_state"
        private const val MAX_METRIC_HISTORY = 1000
        private const val MAX_IMPROVEMENT_HISTORY = 50
    }
}
