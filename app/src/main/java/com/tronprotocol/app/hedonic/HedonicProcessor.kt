package com.tronprotocol.app.hedonic

import android.content.Context
import android.util.Log
import com.tronprotocol.app.affect.AffectInput
import com.tronprotocol.app.affect.AffectState

/**
 * Hedonic signal processor: the complete pipeline from sensory input to affect.
 *
 * Signal flow (from the Hedonic Architecture Specification):
 * 1. Physical stimulus contacts skin surface
 * 2. Sensor array reports: pressure vector, temperature, moisture, texture, rhythm
 * 3. Somatosensory preprocessor identifies stimulus type and body zone
 * 4. Hedonic weight lookup: base_weight for zone
 * 5. Consent gate computation: min(safety, attachment, volition)
 * 6. Arousal modifier computation: 1.0 + (arousal × sensitivity_gain)
 * 7. Effective hedonic signal = base_weight × consent_gate × arousal_modifier
 * 8. Signal injected into AffectEngine valence dimension
 *
 * Total skin-to-response latency target: ~135 milliseconds.
 */
class HedonicProcessor(private val context: Context) {

    val consentGate = ConsentGate()
    private val hedonicLearning = HedonicLearning(context)

    /** Sensitivity gain for arousal feedback loop. Default 1.5. */
    var sensitivityGain: Float = DEFAULT_SENSITIVITY_GAIN

    /** Overload protection threshold. */
    private var overloadAccumulator: Float = 0.0f

    /** Whether the system is in refractory period (post-peak). */
    @Volatile var isRefractory: Boolean = false
        private set

    /** Current arousal phase for peak response tracking. */
    @Volatile var currentPhase: ArousalPhase = ArousalPhase.BASELINE
        private set

    /** Previous arousal value for rate-of-change detection. */
    private var previousArousal: Float = 0.0f

    /**
     * Process a hedonic stimulus and return the effective signal strength.
     *
     * @param zone The body zone receiving stimulation
     * @param intensity Raw stimulus intensity (0.0–1.0)
     * @param currentState Current affect state (for arousal modifier)
     * @param partnerId Optional partner identifier for partner-specific learning
     * @return The effective hedonic signal to inject into AffectEngine valence
     */
    fun processStimulus(
        zone: BodyZone,
        intensity: Float,
        currentState: AffectState,
        partnerId: String? = null
    ): HedonicSignal {
        val baseWeight = zone.baseHedonicWeight
        val learnedModifier = hedonicLearning.getLearnedWeight(zone)
        val partnerModifier = if (partnerId != null) {
            hedonicLearning.getPartnerModifier(zone, partnerId)
        } else 0.0f

        val effectiveBaseWeight = (baseWeight + learnedModifier + partnerModifier)
            .coerceIn(0.0f, 1.0f)

        // Consent gate (multiplicative — zero gate means zero signal).
        val gateValue = consentGate.gateValue

        // Arousal modifier: 1.0 + (arousal × sensitivity_gain).
        val arousal = currentState.arousal
        val arousalModifier = if (isRefractory) {
            REFRACTORY_SENSITIVITY // Reduced sensitivity during refractory period
        } else {
            1.0f + (arousal * sensitivityGain)
        }

        // Effective hedonic signal.
        var effectiveSignal = effectiveBaseWeight * gateValue * arousalModifier * intensity

        // Overload protection.
        overloadAccumulator = (overloadAccumulator + effectiveSignal * 0.1f)
            .coerceAtMost(OVERLOAD_THRESHOLD * 1.5f)
        if (overloadAccumulator > OVERLOAD_THRESHOLD) {
            effectiveSignal *= OVERLOAD_DAMPING
            overloadAccumulator *= 0.9f // Slowly recover
        }
        // Passive decay of overload accumulator.
        overloadAccumulator = (overloadAccumulator - OVERLOAD_DECAY_RATE)
            .coerceAtLeast(0.0f)

        // Track arousal phase transitions.
        updateArousalPhase(arousal)
        previousArousal = arousal

        return HedonicSignal(
            zone = zone,
            effectiveSignal = effectiveSignal,
            baseWeight = baseWeight,
            gateValue = gateValue,
            arousalModifier = arousalModifier,
            intensity = intensity,
            phase = currentPhase
        )
    }

    /**
     * Create an AffectInput from a hedonic signal for injection into AffectEngine.
     */
    fun signalToAffectInput(signal: HedonicSignal): AffectInput {
        return AffectInput.builder("hedonic:${signal.zone.label}")
            .valence(signal.effectiveSignal)
            .arousal(signal.effectiveSignal * 0.3f) // Positive hedonic signal raises arousal
            .build()
    }

    /**
     * Track arousal phase and detect peak response conditions.
     */
    private fun updateArousalPhase(currentArousal: Float) {
        val rateOfChange = currentArousal - previousArousal

        currentPhase = when {
            isRefractory -> {
                if (currentArousal < REFRACTORY_EXIT_THRESHOLD) {
                    isRefractory = false
                    ArousalPhase.AFTERGLOW
                } else {
                    ArousalPhase.RESOLUTION
                }
            }
            rateOfChange > PEAK_TRIGGER_RATE && currentArousal > PEAK_AROUSAL_THRESHOLD -> {
                isRefractory = true
                ArousalPhase.CASCADE
            }
            currentArousal > 0.8f -> ArousalPhase.APPROACHING_PEAK
            currentArousal > 0.5f -> ArousalPhase.ELEVATED
            currentArousal > 0.2f -> ArousalPhase.WARMING
            else -> ArousalPhase.BASELINE
        }
    }

    /**
     * Record the outcome of a hedonic encounter for learning.
     */
    fun recordEncounterOutcome(
        zone: BodyZone,
        finalValence: Float,
        gateWasOpen: Boolean,
        partnerId: String? = null
    ) {
        hedonicLearning.updateWeight(zone, finalValence, gateWasOpen)
        if (partnerId != null) {
            hedonicLearning.updatePartnerModifier(zone, partnerId, finalValence, gateWasOpen)
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "consent_gate" to consentGate.gateValue,
        "gate_state" to consentGate.gateState.label,
        "current_phase" to currentPhase.label,
        "is_refractory" to isRefractory,
        "overload_accumulator" to overloadAccumulator,
        "sensitivity_gain" to sensitivityGain
    )

    /**
     * Arousal phases tracking the approach to and recovery from peak.
     */
    enum class ArousalPhase(val label: String) {
        BASELINE("baseline"),
        WARMING("warming"),
        ELEVATED("elevated"),
        APPROACHING_PEAK("approaching_peak"),
        CASCADE("cascade"),
        PLATEAU("plateau"),
        RESOLUTION("resolution"),
        AFTERGLOW("afterglow")
    }

    /**
     * Result of processing a hedonic stimulus.
     */
    data class HedonicSignal(
        val zone: BodyZone,
        val effectiveSignal: Float,
        val baseWeight: Float,
        val gateValue: Float,
        val arousalModifier: Float,
        val intensity: Float,
        val phase: ArousalPhase
    )

    companion object {
        private const val TAG = "HedonicProcessor"
        private const val DEFAULT_SENSITIVITY_GAIN = 1.5f
        private const val OVERLOAD_THRESHOLD = 5.0f
        private const val OVERLOAD_DAMPING = 0.3f
        private const val OVERLOAD_DECAY_RATE = 0.01f
        private const val REFRACTORY_SENSITIVITY = 0.3f
        private const val REFRACTORY_EXIT_THRESHOLD = 0.2f
        private const val PEAK_TRIGGER_RATE = 0.15f
        private const val PEAK_AROUSAL_THRESHOLD = 0.9f
    }
}
