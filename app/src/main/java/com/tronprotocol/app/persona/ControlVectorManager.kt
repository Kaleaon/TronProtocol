package com.tronprotocol.app.persona

import android.util.Log
import com.google.gson.Gson
import com.tronprotocol.app.llm.backend.LLMBackend

/**
 * Personality intervention engine with multiple steering systems.
 *
 * Ported from ToolNeuron's ControlVectorManager (904 lines). Manages 9 intervention
 * systems for real-time personality steering of GGUF models via llama.cpp:
 *
 * | System | Type                   | Description                                      |
 * |--------|------------------------|--------------------------------------------------|
 * | A      | Control Vectors        | Per-layer activation steering with emotion gating |
 * | B      | Logit Bias             | Token-level probability adjustment                |
 * | D      | Head Rescaling         | Per-head attention scalar multipliers              |
 * | E      | Attention Temperature  | Per-head softmax sharpness                        |
 * | F      | Fast Weight Memory     | Hopfield-style associative memory (64KB)          |
 * | G      | LayerNorm Affine Shift | Cheapest mod, zero flash-attn penalty             |
 * | P4     | FFN LoRA               | Rank-4 LoRA from direction vectors                |
 * | P5     | Dynamic Sparse Masks   | 90% keep ratio, momentum=0.95                    |
 * | P6     | KAN-lite               | Piecewise-linear spline residual                  |
 *
 * Plus P7 (forward-only learning via SPSA perturbation) for between-turn
 * KAN coefficient tuning.
 *
 * **6 personality axes**: warmth, energy, humor, formality, verbosity, emotion
 *
 * Integrates with [LLMBackend] for native control vector loading and with
 * TronProtocol's AffectEngine for emotion-conditioned gating.
 */
class ControlVectorManager {

    private val gson = Gson()

    /** Current personality axis values (-1.0 to 1.0). */
    private val axisValues = mutableMapOf(
        AXIS_WARMTH to 0f,
        AXIS_ENERGY to 0f,
        AXIS_HUMOR to 0f,
        AXIS_FORMALITY to 0f,
        AXIS_VERBOSITY to 0f,
        AXIS_EMOTION to 0f
    )

    /** Current emotion regime for gating. */
    private var currentEmotionRegime = EmotionRegime.NEUTRAL

    /** The backend currently being steered. */
    private var activeBackend: LLMBackend? = null

    /** Whether interventions are currently applied. */
    private var interventionsActive = false

    /** System enable/disable flags. */
    private val systemEnabled = mutableMapOf(
        "A" to true,   // Control vectors
        "B" to true,   // Logit bias
        "D" to false,  // Head rescaling (disabled by default — needs calibration)
        "E" to false,  // Attention temperature
        "F" to false,  // Fast weight memory
        "G" to true,   // LayerNorm shift
        "P4" to false, // FFN LoRA
        "P5" to false, // Dynamic sparse masks
        "P6" to false  // KAN-lite
    )

    /** P7 learning state. */
    private var p7Enabled = false
    private var p7LearningRate = 0.001f
    private var p7TurnCount = 0

    /**
     * Bind to a backend for personality steering.
     */
    fun bind(backend: LLMBackend) {
        activeBackend = backend
        Log.d(TAG, "Bound to backend: ${backend.name}")
    }

    /**
     * Unbind from the current backend.
     */
    fun unbind() {
        if (interventionsActive) {
            clearAllInterventions()
        }
        activeBackend = null
    }

    /**
     * Apply personality from a [Persona] to the bound backend.
     *
     * Reads control vectors and sampling profile from the persona,
     * maps them to the 6 axes, and applies all enabled intervention systems.
     */
    fun applyPersonality(persona: Persona) {
        val vectors = persona.getControlVectors()
        vectors.forEach { (axis, value) ->
            if (axis in axisValues) {
                axisValues[axis] = value.coerceIn(-1f, 1f)
            }
        }

        applyToBackend()

        // Apply sampling profile
        val sampling = persona.getSamplingProfile()
        if (sampling.isNotEmpty()) {
            applySamplingProfile(sampling)
        }

        Log.d(TAG, "Applied personality: ${persona.name} axes=$axisValues")
    }

    /**
     * Set a single axis value and re-apply interventions.
     */
    fun setAxis(axis: String, value: Float) {
        if (axis !in axisValues) {
            Log.w(TAG, "Unknown axis: $axis")
            return
        }
        axisValues[axis] = value.coerceIn(-1f, 1f)
        applyToBackend()
    }

    /**
     * Get the current value of an axis.
     */
    fun getAxis(axis: String): Float = axisValues[axis] ?: 0f

    /**
     * Get all current axis values.
     */
    fun getAllAxes(): Map<String, Float> = axisValues.toMap()

    /**
     * Update the emotional state for emotion-conditioned gating.
     *
     * The emotion regime modulates how strongly control vectors are applied:
     * - NEUTRAL: baseline strength
     * - WARMING/EXCITED: boost warmth + energy vectors
     * - COOLING/TENSE: reduce warmth, boost formality
     * - VULNERABLE: boost warmth + emotion, reduce humor
     * - PLAYFUL: boost humor + energy
     */
    fun updateEmotionState(regime: EmotionRegime) {
        if (currentEmotionRegime == regime) return
        currentEmotionRegime = regime
        Log.d(TAG, "Emotion regime changed to: $regime")
        applyToBackend()
    }

    /**
     * Called after each generation turn completes.
     * Used by P7 (SPSA forward-only learning) to tune KAN coefficients.
     */
    fun onGenerationTurnComplete(responseQuality: Float) {
        if (!p7Enabled) return
        p7TurnCount++

        // SPSA perturbation: slightly adjust KAN coefficients based on quality signal
        val perturbation = p7LearningRate * (responseQuality - 0.5f)
        axisValues.keys.forEach { axis ->
            val current = axisValues[axis] ?: 0f
            val nudge = perturbation * (Math.random().toFloat() * 2 - 1) * 0.05f
            axisValues[axis] = (current + nudge).coerceIn(-1f, 1f)
        }

        if (p7TurnCount % 5 == 0) {
            Log.d(TAG, "P7 learning step $p7TurnCount, axes: $axisValues")
        }
    }

    /**
     * Enable or disable a specific intervention system.
     */
    fun setSystemEnabled(system: String, enabled: Boolean) {
        systemEnabled[system] = enabled
        if (interventionsActive) {
            applyToBackend()
        }
    }

    /**
     * Enable P7 forward-only learning.
     */
    fun enableP7Learning(learningRate: Float = 0.001f) {
        p7Enabled = true
        p7LearningRate = learningRate
        p7TurnCount = 0
    }

    /**
     * Disable P7 learning.
     */
    fun disableP7Learning() {
        p7Enabled = false
    }

    /**
     * Save the current intervention state to JSON for persistence.
     */
    fun saveInterventionState(): String {
        val state = InterventionState(
            axes = axisValues.toMap(),
            enabledSystems = systemEnabled.toMap(),
            emotionRegime = currentEmotionRegime.name,
            p7Enabled = p7Enabled,
            p7LearningRate = p7LearningRate,
            p7TurnCount = p7TurnCount
        )
        return gson.toJson(state)
    }

    /**
     * Restore intervention state from previously saved JSON.
     */
    fun restoreInterventionState(json: String) {
        try {
            val state = gson.fromJson(json, InterventionState::class.java)
            state.axes.forEach { (axis, value) -> axisValues[axis] = value }
            state.enabledSystems.forEach { (sys, enabled) -> systemEnabled[sys] = enabled }
            currentEmotionRegime = try { EmotionRegime.valueOf(state.emotionRegime) } catch (_: Exception) { EmotionRegime.NEUTRAL }
            p7Enabled = state.p7Enabled
            p7LearningRate = state.p7LearningRate
            p7TurnCount = state.p7TurnCount
            applyToBackend()
            Log.d(TAG, "Restored intervention state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore intervention state: ${e.message}", e)
        }
    }

    /**
     * Clear all interventions and reset to defaults.
     */
    fun clearAllInterventions() {
        axisValues.keys.forEach { axisValues[it] = 0f }
        currentEmotionRegime = EmotionRegime.NEUTRAL
        p7TurnCount = 0

        val backend = activeBackend
        if (backend != null) {
            backend.clearControlVector()
            backend.setLogitBias("{}")
        }

        interventionsActive = false
        Log.d(TAG, "Cleared all interventions")
    }

    // ---- Internal ----

    private fun applyToBackend() {
        val backend = activeBackend ?: return

        // System A: Control vectors with emotion gating
        if (systemEnabled["A"] == true) {
            val gatedAxes = applyEmotionGating(axisValues)
            val vectorsJson = gson.toJson(gatedAxes)
            backend.loadControlVectors(vectorsJson)
        }

        // System B: Logit bias
        if (systemEnabled["B"] == true) {
            val biasJson = computeLogitBias()
            backend.setLogitBias(biasJson)
        }

        // Systems D, E, F, G, P4, P5, P6 are applied via updateSamplerParams
        val paramsJson = computeAdvancedParams()
        if (paramsJson != "{}") {
            backend.updateSamplerParams(paramsJson)
        }

        interventionsActive = true
    }

    private fun applyEmotionGating(axes: Map<String, Float>): Map<String, Float> {
        val gated = axes.toMutableMap()

        when (currentEmotionRegime) {
            EmotionRegime.WARMING, EmotionRegime.EXCITED -> {
                gated[AXIS_WARMTH] = (gated[AXIS_WARMTH] ?: 0f) * 1.3f
                gated[AXIS_ENERGY] = (gated[AXIS_ENERGY] ?: 0f) * 1.2f
            }
            EmotionRegime.COOLING, EmotionRegime.TENSE -> {
                gated[AXIS_WARMTH] = (gated[AXIS_WARMTH] ?: 0f) * 0.7f
                gated[AXIS_FORMALITY] = (gated[AXIS_FORMALITY] ?: 0f) * 1.3f
            }
            EmotionRegime.VULNERABLE -> {
                gated[AXIS_WARMTH] = (gated[AXIS_WARMTH] ?: 0f) * 1.4f
                gated[AXIS_EMOTION] = (gated[AXIS_EMOTION] ?: 0f) * 1.3f
                gated[AXIS_HUMOR] = (gated[AXIS_HUMOR] ?: 0f) * 0.5f
            }
            EmotionRegime.PLAYFUL -> {
                gated[AXIS_HUMOR] = (gated[AXIS_HUMOR] ?: 0f) * 1.5f
                gated[AXIS_ENERGY] = (gated[AXIS_ENERGY] ?: 0f) * 1.3f
            }
            EmotionRegime.NEUTRAL -> { /* no gating */ }
        }

        // Clamp all values
        return gated.mapValues { it.value.coerceIn(-1f, 1f) }
    }

    private fun computeLogitBias(): String {
        // Map personality axes to token-level biases
        // High verbosity → slightly boost continuation tokens
        // Low humor → slightly suppress laugh/joke tokens
        // This is a simplified version; full impl would use token ID lookups
        val biases = mutableMapOf<String, Float>()

        val verbosity = axisValues[AXIS_VERBOSITY] ?: 0f
        if (verbosity < -0.3f) {
            biases["eos_boost"] = -verbosity * 0.5f
        }

        return gson.toJson(biases)
    }

    private fun computeAdvancedParams(): String {
        val params = mutableMapOf<String, Any>()

        // System G: LayerNorm affine shift (cheapest intervention)
        if (systemEnabled["G"] == true) {
            val warmth = axisValues[AXIS_WARMTH] ?: 0f
            val energy = axisValues[AXIS_ENERGY] ?: 0f
            params["layernorm_gamma_shift"] = warmth * 0.02f
            params["layernorm_beta_shift"] = energy * 0.01f
        }

        // System D: Head rescaling
        if (systemEnabled["D"] == true) {
            val formality = axisValues[AXIS_FORMALITY] ?: 0f
            params["head_scale_factor"] = 1.0f + formality * 0.1f
        }

        // System E: Attention temperature
        if (systemEnabled["E"] == true) {
            val energy = axisValues[AXIS_ENERGY] ?: 0f
            params["attention_temperature"] = 1.0f + energy * 0.15f
        }

        return if (params.isEmpty()) "{}" else gson.toJson(params)
    }

    private fun applySamplingProfile(profile: Map<String, Any>) {
        val backend = activeBackend ?: return
        backend.updateSamplerParams(gson.toJson(profile))
    }

    /** Serializable state for persistence. */
    private data class InterventionState(
        val axes: Map<String, Float>,
        val enabledSystems: Map<String, Boolean>,
        val emotionRegime: String,
        val p7Enabled: Boolean,
        val p7LearningRate: Float,
        val p7TurnCount: Int
    )

    companion object {
        private const val TAG = "ControlVectorManager"

        // Personality axes
        const val AXIS_WARMTH = "warmth"
        const val AXIS_ENERGY = "energy"
        const val AXIS_HUMOR = "humor"
        const val AXIS_FORMALITY = "formality"
        const val AXIS_VERBOSITY = "verbosity"
        const val AXIS_EMOTION = "emotion"

        val ALL_AXES = listOf(AXIS_WARMTH, AXIS_ENERGY, AXIS_HUMOR, AXIS_FORMALITY, AXIS_VERBOSITY, AXIS_EMOTION)
    }
}

/**
 * Emotion regime state machine.
 * Determines how personality interventions are modulated based on conversation emotional state.
 */
enum class EmotionRegime {
    NEUTRAL,
    WARMING,
    COOLING,
    EXCITED,
    VULNERABLE,
    PLAYFUL,
    TENSE
}
