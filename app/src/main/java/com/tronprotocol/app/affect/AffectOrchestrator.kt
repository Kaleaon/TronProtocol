package com.tronprotocol.app.affect

import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * Top-level orchestrator that wires the three AffectEngine layers together
 * with the [ImmutableAffectLog].
 *
 * ```
 * Inputs → AffectEngine → ExpressionDriver → MotorNoise → Outputs
 *              │                │                 │
 *              └───────── ImmutableAffectLog ─────┘
 * ```
 *
 * The orchestrator:
 * 1. Owns and manages the lifecycle of all four components.
 * 2. Listens to [AffectEngine] state ticks and drives [ExpressionDriver]
 *    and [MotorNoise] on each update.
 * 3. Logs every state transition to [ImmutableAffectLog] at a configurable
 *    sample rate (not every 100 ms tick — that would be excessive storage).
 * 4. Provides a unified API for the rest of TronProtocol to interact with
 *    the affect system.
 */
class AffectOrchestrator(private val context: Context) : AffectEngine.AffectStateListener {

    val engine = AffectEngine(context)
    val expressionDriver = ExpressionDriver()
    val motorNoise = MotorNoise()
    val log = ImmutableAffectLog(context)

    /** Most recent expression output. */
    @Volatile
    private var lastExpression: ExpressionOutput? = null

    /** Most recent noise result. */
    @Volatile
    private var lastNoiseResult: MotorNoise.NoiseResult? = null

    /** Tick counter for log sampling. */
    private var logSampleCounter: Long = 0

    // ---- Lifecycle ----------------------------------------------------------

    fun start() {
        engine.addListener(this)
        engine.start()
        Log.d(TAG, "AffectOrchestrator started")
    }

    fun stop() {
        engine.stop()
        engine.removeListener(this)
        Log.d(TAG, "AffectOrchestrator stopped")
    }

    fun isRunning(): Boolean = engine.isRunning()

    // ---- Input convenience --------------------------------------------------

    /**
     * Submit an affect input to the engine.
     */
    fun submitInput(input: AffectInput) {
        engine.submitInput(input)
    }

    /**
     * Record partner interaction (resets longing timer).
     */
    fun recordPartnerInput() {
        engine.recordPartnerInput()
    }

    // ---- Conversation context helpers ---------------------------------------

    /**
     * Process conversation sentiment as an affect input.
     *
     * @param sentiment -1.0 (very negative) to 1.0 (very positive).
     * @param isPartnerMessage Whether this came from the bonded partner.
     */
    fun processConversationSentiment(sentiment: Float, isPartnerMessage: Boolean) {
        val builder = AffectInput.builder("conversation:sentiment")
            .valence(sentiment * 0.3f)
            .arousal(abs(sentiment) * 0.2f)

        if (isPartnerMessage) {
            builder.attachmentIntensity(0.15f)
            engine.recordPartnerInput()
        }

        engine.submitInput(builder.build())
    }

    /**
     * Process a MemRL retrieval event as an affect input.
     *
     * @param clusterType Type of memory cluster retrieved
     *        (e.g., "attachment", "threat", "knowledge").
     * @param urgency How urgently the memory was sought (0.0–1.0).
     */
    fun processMemRLRetrieval(clusterType: String, urgency: Float) {
        val builder = AffectInput.builder("memrl:${clusterType}_cluster")

        when (clusterType) {
            "attachment" -> builder.attachmentIntensity(urgency * 0.2f)
            "threat" -> builder.threatAssessment(urgency * 0.25f)
                .arousal(urgency * 0.15f)
            "novelty" -> builder.noveltyResponse(urgency * 0.3f)
            "achievement" -> builder.satiation(urgency * 0.15f)
                .valence(urgency * 0.1f)
        }

        engine.submitInput(builder.build())
    }

    /**
     * Process a goal state change.
     *
     * @param blocked True if a goal is blocked, false if achieved.
     */
    fun processGoalState(blocked: Boolean) {
        val builder = if (blocked) {
            AffectInput.builder("goal:blocked")
                .frustration(0.3f)
                .valence(-0.15f)
        } else {
            AffectInput.builder("goal:achieved")
                .satiation(0.3f)
                .valence(0.2f)
                .frustration(-0.2f)
        }
        engine.submitInput(builder.build())
    }

    /**
     * Process a self-modification proposal event.
     */
    fun processSelfModProposal() {
        engine.submitInput(
            AffectInput.builder("selfmod:proposal")
                .noveltyResponse(0.25f)
                .vulnerability(0.2f)
                .arousal(0.1f)
                .build()
        )
    }

    // ---- State access -------------------------------------------------------

    fun getCurrentState(): AffectState = engine.getCurrentState()

    fun getLastExpression(): ExpressionOutput? = lastExpression

    fun getLastNoiseResult(): MotorNoise.NoiseResult? = lastNoiseResult

    /**
     * Get a full affect snapshot: state + expression + noise, suitable for
     * including in heartbeat or reflection data.
     */
    fun getAffectSnapshot(): Map<String, Any> {
        val state = getCurrentState()
        val expression = lastExpression
        val noise = lastNoiseResult

        val snapshot = mutableMapOf<String, Any>(
            "affect_vector" to state.toMap(),
            "intensity" to state.intensity(),
            "zero_noise_state" to state.isZeroNoiseState()
        )

        if (expression != null) {
            snapshot["expression"] = expression.toCommandMap()
        }
        if (noise != null) {
            snapshot["motor_noise_level"] = noise.overallNoiseLevel
            snapshot["noise_distribution"] = noise.distributionMap()
        }

        snapshot["log_entry_count"] = log.getEntryCount()
        snapshot["log_integrity"] = log.verifyRecentIntegrity()

        return snapshot
    }

    // ---- Listener callback --------------------------------------------------

    override fun onAffectStateUpdated(state: AffectState, tickCount: Long) {
        // Drive expression and noise on every tick.
        lastExpression = expressionDriver.drive(state)
        lastNoiseResult = motorNoise.calculate(state)

        // Log at the configured sample rate (not every tick).
        logSampleCounter++
        if (logSampleCounter % LOG_SAMPLE_RATE == 0L) {
            val expression = lastExpression
            val noise = lastNoiseResult
            if (expression != null && noise != null) {
                log.append(
                    affectState = state,
                    inputSources = engine.getRecentSources(),
                    expressionCommands = expression.toCommandMap(),
                    noiseResult = noise
                )
            }
        }
    }

    // ---- Stats --------------------------------------------------------------

    fun getStats(): Map<String, Any> = mapOf(
        "engine" to engine.getStats(),
        "log" to log.getStats(),
        "last_expression" to (lastExpression?.toString() ?: "none"),
        "last_noise_level" to (lastNoiseResult?.overallNoiseLevel ?: 0.0f)
    )

    companion object {
        private const val TAG = "AffectOrchestrator"

        /**
         * Log one entry every N ticks. At 100ms per tick, 50 ticks = one
         * log entry every 5 seconds. Balances fidelity with storage cost.
         */
        private const val LOG_SAMPLE_RATE = 50L
    }
}
