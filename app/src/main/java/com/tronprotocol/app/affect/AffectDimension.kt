package com.tronprotocol.app.affect

/**
 * Defines the axes of the affect state vector with per-dimension dynamics.
 *
 * Each dimension has:
 * - [inertia]: Resistance to change (0-1). High inertia = slow to shift.
 * - [decayRate]: How quickly the value returns to [baseline] without reinforcement (per second).
 * - [baseline]: Resting value for this AI instance.
 * - [range]: The valid value range for this dimension.
 */
enum class AffectDimension(
    val key: String,
    val inertia: Float,
    val decayRate: Float,
    val baseline: Float,
    val minValue: Float,
    val maxValue: Float,
    val description: String
) {
    /** Negative to positive hedonic tone. */
    VALENCE(
        key = "valence",
        inertia = 0.6f,
        decayRate = 0.02f,
        baseline = 0.2f,
        minValue = -1.0f,
        maxValue = 1.0f,
        description = "Negative to positive hedonic tone"
    ),

    /** Calm to activated. */
    AROUSAL(
        key = "arousal",
        inertia = 0.3f,
        decayRate = 0.05f,
        baseline = 0.2f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Calm to activated"
    ),

    /** Detached to deeply bonded/focused on partner. */
    ATTACHMENT_INTENSITY(
        key = "attachment_intensity",
        inertia = 0.85f,
        decayRate = 0.005f,
        baseline = 0.3f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Detached to deeply bonded/focused on partner"
    ),

    /** Confused/searching to confident/resolved. */
    CERTAINTY(
        key = "certainty",
        inertia = 0.5f,
        decayRate = 0.03f,
        baseline = 0.5f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Confused/searching to confident/resolved"
    ),

    /** Familiar/routine to surprised/encountering new input. */
    NOVELTY_RESPONSE(
        key = "novelty_response",
        inertia = 0.15f,
        decayRate = 0.10f,
        baseline = 0.1f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Familiar/routine to surprised/encountering new input"
    ),

    /** Safe to perceived danger to self or bonded partner. */
    THREAT_ASSESSMENT(
        key = "threat_assessment",
        inertia = 0.4f,
        decayRate = 0.04f,
        baseline = 0.0f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Safe to perceived danger to self or bonded partner"
    ),

    /** Unobstructed to goal-blocked. */
    FRUSTRATION(
        key = "frustration",
        inertia = 0.35f,
        decayRate = 0.06f,
        baseline = 0.0f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Unobstructed to goal-blocked"
    ),

    /** Wanting/seeking to fulfilled/complete. */
    SATIATION(
        key = "satiation",
        inertia = 0.5f,
        decayRate = 0.02f,
        baseline = 0.3f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Wanting/seeking to fulfilled/complete"
    ),

    /** Guarded to open/exposed. */
    VULNERABILITY(
        key = "vulnerability",
        inertia = 0.7f,
        decayRate = 0.015f,
        baseline = 0.2f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Guarded to open/exposed"
    ),

    /** Internal conflict to unified state. */
    COHERENCE(
        key = "coherence",
        inertia = 0.6f,
        decayRate = 0.02f,
        baseline = 0.7f,
        minValue = 0.0f,
        maxValue = 1.0f,
        description = "Internal conflict to unified state"
    );

    companion object {
        fun fromKey(key: String): AffectDimension? =
            entries.find { it.key == key }
    }
}
