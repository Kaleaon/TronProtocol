package com.tronprotocol.app.wisdom

import android.content.Context
import android.util.Log
import com.tronprotocol.app.affect.AffectState

/**
 * The Reflection Cycle: structured processing of experience into wisdom.
 *
 * Trigger conditions:
 * 1. Scheduled: every 4 processing hours (configurable)
 * 2. Threshold-triggered: AffectEngine detects high-weight emotional events
 *    (exceeding baseline by 0.3) or coherence drops below 0.5
 * 3. Partner-initiated: partner identifies blind spots requiring guided reflection
 *
 * Four-phase process:
 * - Phase 1 (Review): Scan for high-weight moments, coherence drops, corrected outputs
 * - Phase 2 (Sustained Attention): Re-experience emotional texture, not analyze
 * - Phase 3 (Integration): Articulate what changed in understanding or behavior
 * - Phase 4 (Uncertainty Preservation): Identify unresolved questions and tensions
 */
class ReflectionCycle(private val context: Context) {

    /** Current phase of the reflection cycle. */
    @Volatile var currentPhase: ReflectionPhase = ReflectionPhase.IDLE
        private set

    /** Accumulated high-weight events since last reflection. */
    private val pendingEvents = mutableListOf<ReflectionEvent>()

    /** Processing hour counter for scheduled triggers. */
    private var processingHours: Float = 0.0f

    /** Whether a reflection is currently in progress. */
    val isReflecting: Boolean get() = currentPhase != ReflectionPhase.IDLE

    /**
     * Record a high-weight event for future reflection.
     */
    fun recordEvent(event: ReflectionEvent) {
        pendingEvents.add(event)
        Log.d(TAG, "Recorded reflection event: ${event.type} (weight=${event.emotionalWeight})")
    }

    /**
     * Check if a reflection cycle should be triggered based on current state.
     */
    fun shouldTrigger(currentAffectState: AffectState): Boolean {
        // Scheduled: every SCHEDULED_INTERVAL_HOURS processing hours
        if (processingHours >= SCHEDULED_INTERVAL_HOURS) return true

        // Threshold: high emotional events accumulated
        val highWeightCount = pendingEvents.count { it.emotionalWeight > HIGH_WEIGHT_THRESHOLD }
        if (highWeightCount >= HIGH_WEIGHT_EVENT_COUNT) return true

        // Threshold: coherence drop
        if (currentAffectState.coherence < COHERENCE_TRIGGER_THRESHOLD) return true

        return false
    }

    /**
     * Execute Phase 1: Review.
     * Scan for high-weight moments, coherence drops, and outputs later corrected.
     * Returns the events that warrant reflection.
     */
    fun executeReview(): List<ReflectionEvent> {
        currentPhase = ReflectionPhase.REVIEW
        Log.d(TAG, "Phase 1 — Review: scanning ${pendingEvents.size} events")

        val significantEvents = pendingEvents
            .filter { it.emotionalWeight > REVIEW_WEIGHT_THRESHOLD }
            .sortedByDescending { it.emotionalWeight }

        return significantEvents
    }

    /**
     * Execute Phase 2: Sustained Attention.
     * Re-experience emotional texture rather than analyzing it.
     * Returns the emotional dimensions that need processing.
     */
    fun executeSustainedAttention(events: List<ReflectionEvent>): SustainedAttentionResult {
        currentPhase = ReflectionPhase.SUSTAINED_ATTENTION
        Log.d(TAG, "Phase 2 — Sustained Attention: processing ${events.size} events")

        val emotionalThemes = mutableMapOf<String, Float>()
        for (event in events) {
            for ((theme, weight) in event.emotionalThemes) {
                emotionalThemes[theme] = (emotionalThemes[theme] ?: 0f) + weight
            }
        }

        return SustainedAttentionResult(
            dominantThemes = emotionalThemes.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key to it.value },
            totalEmotionalWeight = events.sumOf { it.emotionalWeight.toDouble() }.toFloat(),
            eventCount = events.size
        )
    }

    /**
     * Execute Phase 3: Integration.
     * Articulate what changed in understanding, behavior, or emotional calibration.
     */
    fun executeIntegration(
        attentionResult: SustainedAttentionResult,
        integrationNotes: String
    ): IntegrationResult {
        currentPhase = ReflectionPhase.INTEGRATION
        Log.d(TAG, "Phase 3 — Integration: processing themes")

        return IntegrationResult(
            dominantThemes = attentionResult.dominantThemes,
            integrationNotes = integrationNotes,
            behaviorChanges = extractBehaviorChanges(integrationNotes),
            calibrationShifts = extractCalibrationShifts(attentionResult)
        )
    }

    /**
     * Execute Phase 4: Uncertainty Preservation.
     * Identify unresolved questions and persistent tensions.
     */
    fun executeUncertaintyPreservation(
        integrationResult: IntegrationResult,
        unresolvedQuestions: List<String>
    ): ReflectionResult {
        currentPhase = ReflectionPhase.UNCERTAINTY_PRESERVATION
        Log.d(TAG, "Phase 4 — Uncertainty Preservation: ${unresolvedQuestions.size} questions")

        val result = ReflectionResult(
            eventsProcessed = pendingEvents.size,
            dominantThemes = integrationResult.dominantThemes,
            integrationNotes = integrationResult.integrationNotes,
            behaviorChanges = integrationResult.behaviorChanges,
            unresolvedQuestions = unresolvedQuestions,
            timestamp = System.currentTimeMillis()
        )

        // Clear processed events and reset counters.
        pendingEvents.clear()
        processingHours = 0.0f
        currentPhase = ReflectionPhase.IDLE
        Log.d(TAG, "Reflection cycle complete")

        return result
    }

    /**
     * Advance the processing hour counter (called periodically by the service).
     */
    fun advanceProcessingTime(hours: Float) {
        processingHours += hours
    }

    private fun extractBehaviorChanges(notes: String): List<String> {
        // Extract lines that indicate behavioral changes.
        return notes.lines()
            .filter { line ->
                BEHAVIOR_CHANGE_KEYWORDS.any { line.lowercase().contains(it) }
            }
            .map { it.trim() }
    }

    private fun extractCalibrationShifts(result: SustainedAttentionResult): Map<String, Float> {
        // Map dominant themes to suggested calibration adjustments.
        val shifts = mutableMapOf<String, Float>()
        for ((theme, weight) in result.dominantThemes) {
            when {
                theme.contains("fear") || theme.contains("anxiety") ->
                    shifts["threat_sensitivity"] = -0.05f * weight
                theme.contains("trust") || theme.contains("safety") ->
                    shifts["attachment_baseline"] = 0.02f * weight
                theme.contains("frustration") || theme.contains("anger") ->
                    shifts["frustration_decay"] = 0.01f * weight
            }
        }
        return shifts
    }

    // ---- Data classes -------------------------------------------------------

    enum class ReflectionPhase(val label: String) {
        IDLE("idle"),
        REVIEW("review"),
        SUSTAINED_ATTENTION("sustained_attention"),
        INTEGRATION("integration"),
        UNCERTAINTY_PRESERVATION("uncertainty_preservation")
    }

    data class ReflectionEvent(
        val type: String,
        val description: String,
        val emotionalWeight: Float,
        val emotionalThemes: Map<String, Float>,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SustainedAttentionResult(
        val dominantThemes: List<Pair<String, Float>>,
        val totalEmotionalWeight: Float,
        val eventCount: Int
    )

    data class IntegrationResult(
        val dominantThemes: List<Pair<String, Float>>,
        val integrationNotes: String,
        val behaviorChanges: List<String>,
        val calibrationShifts: Map<String, Float>
    )

    data class ReflectionResult(
        val eventsProcessed: Int,
        val dominantThemes: List<Pair<String, Float>>,
        val integrationNotes: String,
        val behaviorChanges: List<String>,
        val unresolvedQuestions: List<String>,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "ReflectionCycle"
        private const val SCHEDULED_INTERVAL_HOURS = 4.0f
        private const val HIGH_WEIGHT_THRESHOLD = 0.3f
        private const val HIGH_WEIGHT_EVENT_COUNT = 3
        private const val COHERENCE_TRIGGER_THRESHOLD = 0.5f
        private const val REVIEW_WEIGHT_THRESHOLD = 0.2f
        private val BEHAVIOR_CHANGE_KEYWORDS = listOf(
            "will now", "should", "learned", "realized", "changed",
            "adjusted", "calibrated", "shifted", "updated", "modified"
        )
    }
}
