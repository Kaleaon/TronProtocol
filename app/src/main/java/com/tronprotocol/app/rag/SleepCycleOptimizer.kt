package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Sleep-Cycle Self-Optimization Engine
 *
 * Runs during memory consolidation (sleep) cycles to automatically tune
 * hyperparameters through perturbation-based optimization — a simplified
 * variant of Population-Based Training (Jaderberg et al., 2017).
 *
 * Each sleep cycle executes:
 * 1. EVALUATE: Compute composite fitness from retrieval telemetry and Q-value health
 * 2. COMPARE: Check if the last perturbation improved or degraded fitness
 * 3. SELECT: Accept improvements, revert degradations
 * 4. PERTURB: Apply bounded random perturbations for the next wake cycle
 * 5. PERSIST: Store parameters and optimization history to SecureStorage
 *
 * Safety guarantees:
 * - Perturbation magnitude capped at 20% per parameter per cycle
 * - Hard bounds prevent pathological configurations
 * - Threshold ordering enforced: forget < consolidation < strengthen
 * - Automatic reset to defaults after 3 consecutive degradations
 * - Minimum 50 telemetry samples required before optimization begins
 * - Full state persisted across restarts
 */
class SleepCycleOptimizer @Throws(Exception::class) constructor(
    private val context: Context
) {
    private val storage: SecureStorage = SecureStorage(context)

    /** Current tunable parameter configuration. Thread-safe read. */
    @Volatile
    var currentParams: TunableParams = TunableParams()
        private set

    /** Previous cycle's parameters, kept for rollback on degradation. */
    private var previousParams: TunableParams? = null

    /** Fitness score from the previous optimization cycle. Negative means not yet established. */
    private var previousFitness: Float = -1.0f

    // Optimization tracking
    private var optimizationCycles: Int = 0
    private var consecutiveDegradations: Int = 0
    private var totalImprovements: Int = 0
    private var totalReversions: Int = 0
    private val fitnessHistory: MutableList<Float> = mutableListOf()

    init {
        loadState()
    }

    /**
     * Execute one optimization step during a sleep/consolidation cycle.
     *
     * Should be called after telemetry aggregation (Phase 7) so that
     * fresh retrieval metrics are available for fitness evaluation.
     *
     * @param ragStore The RAG store providing telemetry and Q-value statistics
     * @return OptimizationResult describing what was changed and why
     */
    fun optimize(ragStore: RAGStore): OptimizationResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Sleep-cycle optimization step #$optimizationCycles starting...")

        // Step 1: Read telemetry and check if we have enough data
        val snapshot = captureTelemetrySnapshot(ragStore)
        if (snapshot.totalSamples < MIN_TELEMETRY_SAMPLES) {
            Log.d(TAG, "Insufficient telemetry (${snapshot.totalSamples}/$MIN_TELEMETRY_SAMPLES)")
            return OptimizationResult(
                applied = false,
                reason = "insufficient_telemetry",
                sampleCount = snapshot.totalSamples,
                duration = System.currentTimeMillis() - startTime
            )
        }

        // Step 2: Compute current fitness
        val fitness = computeFitness(snapshot, ragStore)

        // Step 3: Compare with previous cycle and decide
        val decision = evaluateAndSelect(fitness)

        // Step 4: Perturb for next cycle
        previousParams = currentParams.copy()
        currentParams = perturb(currentParams)
        previousFitness = fitness
        fitnessHistory.add(fitness)
        if (fitnessHistory.size > MAX_HISTORY) {
            fitnessHistory.removeAt(0)
        }
        optimizationCycles++

        // Step 5: Persist
        try {
            saveState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist optimizer state", e)
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Optimization step complete: fitness=${"%.4f".format(fitness)}, " +
                "decision=${decision.reason}, duration=${duration}ms")

        return OptimizationResult(
            applied = true,
            accepted = decision.accepted,
            reason = decision.reason,
            fitness = fitness,
            previousFitness = if (decision.previousFitness >= 0) decision.previousFitness else null,
            sampleCount = snapshot.totalSamples,
            cycle = optimizationCycles,
            params = currentParams.toMap(),
            duration = duration
        )
    }

    /**
     * Get optimization statistics.
     */
    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>(
            "optimization_cycles" to optimizationCycles,
            "total_improvements" to totalImprovements,
            "total_reversions" to totalReversions,
            "consecutive_degradations" to consecutiveDegradations,
            "current_fitness" to previousFitness
        )
        if (fitnessHistory.size >= 2) {
            stats["fitness_trend"] = fitnessHistory.last() - fitnessHistory.first()
        }
        return stats
    }

    // ====================================================================
    // Fitness Computation
    // ====================================================================

    /**
     * Capture a snapshot of recent retrieval telemetry for fitness evaluation.
     */
    internal fun captureTelemetrySnapshot(ragStore: RAGStore): TelemetrySnapshot {
        val sink = LocalJsonlRetrievalMetricsSink(context, ragStore.getAiId())
        val events = sink.readRecent(TELEMETRY_WINDOW)

        if (events.isEmpty()) {
            return TelemetrySnapshot(0, 0f, 0f, 0f, 0f, emptyMap())
        }

        val byStrategy = events.groupBy { it.strategy }
        val breakdown = byStrategy.mapValues { (_, evts) ->
            StrategySummary(
                count = evts.size,
                avgTopScore = evts.map { it.topScore }.average().toFloat(),
                avgAvgScore = evts.map { it.avgScore }.average().toFloat(),
                emptyRate = evts.count { it.resultCount == 0 }.toFloat() / evts.size,
                avgLatencyMs = evts.map { it.latencyMs }.average().toFloat()
            )
        }

        return TelemetrySnapshot(
            totalSamples = events.size,
            avgTopScore = events.map { it.topScore }.average().toFloat(),
            avgAvgScore = events.map { it.avgScore }.average().toFloat(),
            avgEmptyHitRate = events.count { it.resultCount == 0 }.toFloat() / events.size,
            avgLatencyMs = events.map { it.latencyMs }.average().toFloat(),
            strategyBreakdown = breakdown
        )
    }

    /**
     * Compute composite fitness score from telemetry and Q-value health.
     *
     * Fitness = 0.35 * relevanceQuality
     *         + 0.25 * hitRate
     *         + 0.25 * qValueHealth
     *         + 0.15 * latencyEfficiency
     */
    internal fun computeFitness(snapshot: TelemetrySnapshot, ragStore: RAGStore): Float {
        // Component 1: Retrieval quality (higher top scores = better results)
        val qualityScore = snapshot.avgTopScore.coerceIn(0f, 1f)

        // Component 2: Hit rate (lower empty rate = more successful retrievals)
        val hitRate = (1.0f - snapshot.avgEmptyHitRate).coerceIn(0f, 1f)

        // Component 3: Q-value health (distribution quality + success feedback)
        val memrlStats = ragStore.getMemRLStats()
        val avgQ = (memrlStats["avg_q_value"] as? Float ?: 0.5f).coerceIn(0f, 1f)
        val successRate = (memrlStats["success_rate"] as? Float ?: 0.5f).coerceIn(0f, 1f)
        val qHealth = 0.6f * avgQ + 0.4f * successRate

        // Component 4: Latency efficiency (lower latency = better; sigmoid normalization)
        val latencyScore = (1.0f / (1.0f + snapshot.avgLatencyMs / 1000.0f)).coerceIn(0f, 1f)

        return (FITNESS_W_QUALITY * qualityScore +
                FITNESS_W_HIT_RATE * hitRate +
                FITNESS_W_Q_HEALTH * qHealth +
                FITNESS_W_LATENCY * latencyScore)
    }

    // ====================================================================
    // Selection (accept or revert)
    // ====================================================================

    internal data class SelectionDecision(
        val accepted: Boolean,
        val reason: String,
        val previousFitness: Float
    )

    private fun evaluateAndSelect(newFitness: Float): SelectionDecision {
        if (previousFitness < 0) {
            // First cycle: establish baseline, no comparison possible
            return SelectionDecision(true, "baseline_established", -1f)
        }

        val delta = newFitness - previousFitness

        return if (delta >= -FITNESS_TOLERANCE) {
            // Improved or within noise tolerance: accept current params
            totalImprovements++
            consecutiveDegradations = 0
            previousParams = null
            SelectionDecision(
                accepted = true,
                reason = if (delta > FITNESS_TOLERANCE) "improved" else "within_tolerance",
                previousFitness = previousFitness
            )
        } else {
            // Degradation: revert to previous parameters
            totalReversions++
            consecutiveDegradations++

            previousParams?.let { rollback ->
                currentParams = rollback
                previousParams = null
            }

            // Safety valve: too many consecutive misses -> reset to defaults
            if (consecutiveDegradations >= MAX_CONSECUTIVE_DEGRADATIONS) {
                Log.w(TAG, "Consecutive degradation limit hit — resetting to defaults")
                currentParams = TunableParams()
                consecutiveDegradations = 0
            }

            SelectionDecision(false, "degraded", previousFitness)
        }
    }

    // ====================================================================
    // Perturbation
    // ====================================================================

    /**
     * Apply bounded Gaussian perturbations to all tunable parameters.
     * Maintains threshold ordering: forget < consolidation < strengthen.
     */
    internal fun perturb(params: TunableParams, seed: Long = System.nanoTime()): TunableParams {
        val rng = java.util.Random(seed)

        fun perturbF(value: Float, lo: Float, hi: Float): Float {
            val noise = (rng.nextGaussian().toFloat() * PERTURBATION_STD)
                .coerceIn(-MAX_PERTURBATION, MAX_PERTURBATION)
            return (value * (1.0f + noise)).coerceIn(lo, hi)
        }

        fun perturbI(value: Int, lo: Int, hi: Int): Int {
            val noise = (rng.nextGaussian().toFloat() * PERTURBATION_STD)
                .coerceIn(-MAX_PERTURBATION, MAX_PERTURBATION)
            return (value * (1.0f + noise)).toInt().coerceIn(lo, hi)
        }

        val perturbed = params.copy(
            learningRate = perturbF(params.learningRate, 0.01f, 0.5f),
            strengthenThreshold = perturbF(params.strengthenThreshold, 0.5f, 0.95f),
            consolidationThreshold = perturbF(params.consolidationThreshold, 0.15f, 0.6f),
            forgetThreshold = perturbF(params.forgetThreshold, 0.03f, 0.3f),
            maxForgetPerCycle = perturbI(params.maxForgetPerCycle, 1, 20),
            connectionSimilarityThreshold = perturbF(params.connectionSimilarityThreshold, 0.1f, 0.7f)
        )

        // Enforce ordering: forgetThreshold < consolidationThreshold < strengthenThreshold
        return enforceThresholdOrdering(perturbed)
    }

    /**
     * Repair threshold ordering after perturbation.
     * Ensures forget < consolidation < strengthen with minimum gaps.
     */
    internal fun enforceThresholdOrdering(params: TunableParams): TunableParams {
        var forget = params.forgetThreshold
        var consolidation = params.consolidationThreshold
        var strengthen = params.strengthenThreshold

        // Enforce minimum gap between each level
        if (forget >= consolidation - MIN_THRESHOLD_GAP) {
            forget = consolidation - MIN_THRESHOLD_GAP
        }
        if (consolidation >= strengthen - MIN_THRESHOLD_GAP) {
            consolidation = strengthen - MIN_THRESHOLD_GAP
        }
        // Re-check forget after consolidation adjustment
        if (forget >= consolidation - MIN_THRESHOLD_GAP) {
            forget = consolidation - MIN_THRESHOLD_GAP
        }

        return params.copy(
            forgetThreshold = forget.coerceAtLeast(0.03f),
            consolidationThreshold = consolidation.coerceIn(
                forget + MIN_THRESHOLD_GAP,
                strengthen - MIN_THRESHOLD_GAP
            ),
            strengthenThreshold = strengthen.coerceAtLeast(consolidation + MIN_THRESHOLD_GAP)
        )
    }

    // ====================================================================
    // Persistence
    // ====================================================================

    @Throws(Exception::class)
    private fun saveState() {
        val state = JSONObject().apply {
            put("params", currentParams.toJson())
            previousParams?.let { put("previousParams", it.toJson()) }
            put("previousFitness", previousFitness.toDouble())
            put("optimizationCycles", optimizationCycles)
            put("consecutiveDegradations", consecutiveDegradations)
            put("totalImprovements", totalImprovements)
            put("totalReversions", totalReversions)
            put("fitnessHistory", JSONArray(fitnessHistory.map { it.toDouble() }))
        }
        storage.store(STATE_KEY, state.toString())
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(STATE_KEY) ?: return
            val state = JSONObject(data)

            if (state.has("params")) {
                currentParams = TunableParams.fromJson(state.getJSONObject("params"))
            }
            if (state.has("previousParams")) {
                previousParams = TunableParams.fromJson(state.getJSONObject("previousParams"))
            }
            previousFitness = state.optDouble("previousFitness", -1.0).toFloat()
            optimizationCycles = state.optInt("optimizationCycles", 0)
            consecutiveDegradations = state.optInt("consecutiveDegradations", 0)
            totalImprovements = state.optInt("totalImprovements", 0)
            totalReversions = state.optInt("totalReversions", 0)

            if (state.has("fitnessHistory")) {
                val arr = state.getJSONArray("fitnessHistory")
                fitnessHistory.clear()
                for (i in 0 until arr.length()) {
                    fitnessHistory.add(arr.getDouble(i).toFloat())
                }
            }
            Log.d(TAG, "Loaded optimizer state: cycle=$optimizationCycles, fitness=$previousFitness")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load optimizer state — starting fresh", e)
        }
    }

    // ====================================================================
    // Data Classes
    // ====================================================================

    /**
     * Tunable hyperparameters for the RAG consolidation pipeline.
     * Defaults match the original hard-coded constants.
     */
    data class TunableParams(
        /** Q-value update step size (TD learning rate). */
        val learningRate: Float = DEFAULT_LEARNING_RATE,

        /** Q-value above which chunks receive positive reinforcement during consolidation. */
        val strengthenThreshold: Float = DEFAULT_STRENGTHEN_THRESHOLD,

        /** Q-value below which chunks receive negative feedback during consolidation. */
        val consolidationThreshold: Float = DEFAULT_CONSOLIDATION_THRESHOLD,

        /** Q-value below which chunks are eligible for active forgetting. */
        val forgetThreshold: Float = DEFAULT_FORGET_THRESHOLD,

        /** Maximum chunks removed per consolidation cycle. */
        val maxForgetPerCycle: Int = DEFAULT_MAX_FORGET,

        /** Minimum cosine similarity for creating memory connections. */
        val connectionSimilarityThreshold: Float = DEFAULT_CONNECTION_SIMILARITY
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("learningRate", learningRate.toDouble())
            put("strengthenThreshold", strengthenThreshold.toDouble())
            put("consolidationThreshold", consolidationThreshold.toDouble())
            put("forgetThreshold", forgetThreshold.toDouble())
            put("maxForgetPerCycle", maxForgetPerCycle)
            put("connectionSimilarityThreshold", connectionSimilarityThreshold.toDouble())
        }

        fun toMap(): Map<String, Any> = mapOf(
            "learningRate" to learningRate,
            "strengthenThreshold" to strengthenThreshold,
            "consolidationThreshold" to consolidationThreshold,
            "forgetThreshold" to forgetThreshold,
            "maxForgetPerCycle" to maxForgetPerCycle,
            "connectionSimilarityThreshold" to connectionSimilarityThreshold
        )

        companion object {
            const val DEFAULT_LEARNING_RATE = 0.1f
            const val DEFAULT_STRENGTHEN_THRESHOLD = 0.7f
            const val DEFAULT_CONSOLIDATION_THRESHOLD = 0.3f
            const val DEFAULT_FORGET_THRESHOLD = 0.15f
            const val DEFAULT_MAX_FORGET = 5
            const val DEFAULT_CONNECTION_SIMILARITY = 0.3f

            fun fromJson(json: JSONObject): TunableParams = TunableParams(
                learningRate = json.optDouble("learningRate", DEFAULT_LEARNING_RATE.toDouble()).toFloat(),
                strengthenThreshold = json.optDouble("strengthenThreshold", DEFAULT_STRENGTHEN_THRESHOLD.toDouble()).toFloat(),
                consolidationThreshold = json.optDouble("consolidationThreshold", DEFAULT_CONSOLIDATION_THRESHOLD.toDouble()).toFloat(),
                forgetThreshold = json.optDouble("forgetThreshold", DEFAULT_FORGET_THRESHOLD.toDouble()).toFloat(),
                maxForgetPerCycle = json.optInt("maxForgetPerCycle", DEFAULT_MAX_FORGET),
                connectionSimilarityThreshold = json.optDouble("connectionSimilarityThreshold", DEFAULT_CONNECTION_SIMILARITY.toDouble()).toFloat()
            )
        }
    }

    data class TelemetrySnapshot(
        val totalSamples: Int,
        val avgTopScore: Float,
        val avgAvgScore: Float,
        val avgEmptyHitRate: Float,
        val avgLatencyMs: Float,
        val strategyBreakdown: Map<String, StrategySummary>
    )

    data class StrategySummary(
        val count: Int,
        val avgTopScore: Float,
        val avgAvgScore: Float,
        val emptyRate: Float,
        val avgLatencyMs: Float
    )

    data class OptimizationResult(
        val applied: Boolean,
        val accepted: Boolean = false,
        val reason: String,
        val fitness: Float = 0f,
        val previousFitness: Float? = null,
        val sampleCount: Int = 0,
        val cycle: Int = 0,
        val params: Map<String, Any> = emptyMap(),
        val duration: Long = 0
    ) {
        override fun toString(): String =
            "OptimizationResult{applied=$applied, accepted=$accepted, reason=$reason, " +
                    "fitness=${"%.4f".format(fitness)}, samples=$sampleCount, cycle=$cycle, " +
                    "duration=${duration}ms}"
    }

    companion object {
        private const val TAG = "SleepCycleOptimizer"
        private const val STATE_KEY = "sleep_cycle_optimizer_state"

        /** Minimum telemetry events before optimization kicks in. */
        internal const val MIN_TELEMETRY_SAMPLES = 50

        /** Number of recent telemetry events to consider for fitness. */
        private const val TELEMETRY_WINDOW = 500

        /** Max fitness history entries to retain. */
        private const val MAX_HISTORY = 100

        // Perturbation bounds
        /** Standard deviation for Gaussian perturbation (fraction of parameter value). */
        internal const val PERTURBATION_STD = 0.1f

        /** Maximum perturbation magnitude (caps Gaussian tails). */
        internal const val MAX_PERTURBATION = 0.2f

        /** Minimum gap between consecutive thresholds. */
        internal const val MIN_THRESHOLD_GAP = 0.1f

        // Fitness function weights (sum to 1.0)
        internal const val FITNESS_W_QUALITY = 0.35f
        internal const val FITNESS_W_HIT_RATE = 0.25f
        internal const val FITNESS_W_Q_HEALTH = 0.25f
        internal const val FITNESS_W_LATENCY = 0.15f

        /** Fitness delta below this magnitude is treated as noise. */
        internal const val FITNESS_TOLERANCE = 0.005f

        /** Reset to defaults after this many consecutive degradations. */
        private const val MAX_CONSECUTIVE_DEGRADATIONS = 3
    }
}
