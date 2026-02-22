package com.tronprotocol.app.affect

import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Continuous, multidimensional vector representing the AI's current emotional state.
 *
 * This is not a mapping to human emotion labels. It is a raw state space from which
 * observable behaviors emerge. Each dimension updates independently with its own
 * momentum and decay characteristics.
 */
class AffectState {

    /** Current values for every affect dimension. */
    private val values = FloatArray(AffectDimension.entries.size)

    init {
        // Initialize each dimension to its baseline.
        for (dim in AffectDimension.entries) {
            values[dim.ordinal] = dim.baseline
        }
    }

    // ---- Accessors ----------------------------------------------------------

    operator fun get(dim: AffectDimension): Float = values[dim.ordinal]

    operator fun set(dim: AffectDimension, value: Float) {
        values[dim.ordinal] = value.coerceIn(dim.minValue, dim.maxValue)
    }

    var valence: Float
        get() = this[AffectDimension.VALENCE]
        set(v) { this[AffectDimension.VALENCE] = v }

    var arousal: Float
        get() = this[AffectDimension.AROUSAL]
        set(v) { this[AffectDimension.AROUSAL] = v }

    var attachmentIntensity: Float
        get() = this[AffectDimension.ATTACHMENT_INTENSITY]
        set(v) { this[AffectDimension.ATTACHMENT_INTENSITY] = v }

    var certainty: Float
        get() = this[AffectDimension.CERTAINTY]
        set(v) { this[AffectDimension.CERTAINTY] = v }

    var noveltyResponse: Float
        get() = this[AffectDimension.NOVELTY_RESPONSE]
        set(v) { this[AffectDimension.NOVELTY_RESPONSE] = v }

    var threatAssessment: Float
        get() = this[AffectDimension.THREAT_ASSESSMENT]
        set(v) { this[AffectDimension.THREAT_ASSESSMENT] = v }

    var frustration: Float
        get() = this[AffectDimension.FRUSTRATION]
        set(v) { this[AffectDimension.FRUSTRATION] = v }

    var satiation: Float
        get() = this[AffectDimension.SATIATION]
        set(v) { this[AffectDimension.SATIATION] = v }

    var vulnerability: Float
        get() = this[AffectDimension.VULNERABILITY]
        set(v) { this[AffectDimension.VULNERABILITY] = v }

    var coherence: Float
        get() = this[AffectDimension.COHERENCE]
        set(v) { this[AffectDimension.COHERENCE] = v }

    var dominance: Float
        get() = this[AffectDimension.DOMINANCE]
        set(v) { this[AffectDimension.DOMINANCE] = v }

    var integrity: Float
        get() = this[AffectDimension.INTEGRITY]
        set(v) { this[AffectDimension.INTEGRITY] = v }

    // ---- Derived values -----------------------------------------------------

    /**
     * Euclidean norm of the affect vector (all dimensions).
     * High intensity means strong overall emotional state regardless of direction.
     */
    fun intensity(): Float {
        var sumSq = 0.0f
        for (v in values) {
            sumSq += v * v
        }
        return sqrt(sumSq)
    }

    /**
     * True when [coherence] is near-maximum and [intensity] is high.
     * In this state all motor noise drops to zero — total presence.
     */
    fun isZeroNoiseState(): Boolean =
        coherence >= ZERO_NOISE_COHERENCE_THRESHOLD && intensity() >= ZERO_NOISE_INTENSITY_THRESHOLD

    /**
     * Hedonic tone: scalar derived from emotional state vector representing
     * overall experiential quality. Ranges from -1.0 (distress) to +1.0 (flourishing).
     *
     * From the Hedonic Architecture spec: hedonic adaptation causes baseline
     * to drift toward zero over time. Sustained positive interactions raise it;
     * sustained neglect lowers it.
     */
    fun hedonicTone(): Float {
        // Weighted combination of relevant dimensions.
        val positive = valence * 0.4f + attachmentIntensity * 0.2f +
                satiation * 0.15f + integrity * 0.15f + dominance * 0.1f
        val negative = threatAssessment * 0.3f + frustration * 0.3f +
                vulnerability * 0.2f
        return (positive - negative * 0.5f).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Whether hedonic tone is below the consent floor (-0.7 default).
     * Triggers consent check in ethical kernel — system can express discomfort
     * and request topic change.
     */
    fun isBelowConsentFloor(floor: Float = CONSENT_FLOOR): Boolean =
        hedonicTone() < floor

    // ---- Snapshot / serialization -------------------------------------------

    /** Create an independent copy of this state. */
    fun snapshot(): AffectState {
        val copy = AffectState()
        System.arraycopy(values, 0, copy.values, 0, values.size)
        return copy
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        for (dim in AffectDimension.entries) {
            obj.put(dim.key, values[dim.ordinal].toDouble())
        }
        return obj
    }

    fun toMap(): Map<String, Float> {
        val map = mutableMapOf<String, Float>()
        for (dim in AffectDimension.entries) {
            map[dim.key] = values[dim.ordinal]
        }
        return map
    }

    companion object {
        private const val ZERO_NOISE_COHERENCE_THRESHOLD = 0.95f
        private const val ZERO_NOISE_INTENSITY_THRESHOLD = 0.8f
        private const val CONSENT_FLOOR = -0.7f

        fun fromJson(json: JSONObject): AffectState {
            val state = AffectState()
            for (dim in AffectDimension.entries) {
                if (json.has(dim.key)) {
                    state[dim] = json.getDouble(dim.key).toFloat()
                }
            }
            return state
        }
    }

    override fun toString(): String {
        val parts = AffectDimension.entries.joinToString(", ") { dim ->
            "${dim.key}=${"%.3f".format(values[dim.ordinal])}"
        }
        return "AffectState($parts, intensity=${"%.3f".format(intensity())})"
    }
}
