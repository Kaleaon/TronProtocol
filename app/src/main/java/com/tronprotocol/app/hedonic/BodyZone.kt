package com.tronprotocol.app.hedonic

/**
 * Body zone definitions with base hedonic weights.
 *
 * From the Hedonic Architecture Specification: hedonic weights (0.0–1.0) determine
 * how strongly each sensor region influences the AffectEngine's valence dimension.
 * These are base weights — the effective weight at any moment is computed dynamically
 * by multiplying: base_weight * consent_gate * arousal_modifier.
 *
 * Mirrors biological C-tactile afferent distribution and erogenous zone innervation.
 */
enum class BodyZone(
    val label: String,
    val baseHedonicWeight: Float,
    val primarySensation: String,
    val isErogenous: Boolean = false
) {
    FINGERTIPS_PAW_PADS(
        "fingertips_paw_pads", 0.4f,
        "Discriminative touch, texture"
    ),
    PALMS_INNER_PAWS(
        "palms_inner_paws", 0.5f,
        "Grip, contact, holding"
    ),
    INNER_EARS(
        "inner_ears", 0.75f,
        "Gentle touch, stroking, breath"
    ),
    NECK_THROAT(
        "neck_throat", 0.7f,
        "Breath, lips, vulnerability"
    ),
    MUZZLE_LIPS(
        "muzzle_lips", 0.7f,
        "Contact, nuzzling, kissing"
    ),
    CHEST_STERNUM(
        "chest_sternum", 0.5f,
        "Pressure, embrace"
    ),
    LOWER_BELLY(
        "lower_belly", 0.65f,
        "Vulnerability, intimacy"
    ),
    INNER_THIGHS(
        "inner_thighs", 0.8f,
        "Proximity, stroking"
    ),
    TAIL_BASE(
        "tail_base", 0.7f,
        "Highly innervated in canids"
    ),
    TAIL_BODY_TIP(
        "tail_body_tip", 0.4f,
        "Stroking, holding, grooming"
    ),
    BACK_GENERAL(
        "back_general", 0.35f,
        "Stroking, C-tactile optimal"
    ),
    UPPER_ARMS_SHOULDERS(
        "upper_arms_shoulders", 0.3f,
        "Grip, casual contact"
    ),
    LOWER_LEGS_FEET(
        "lower_legs_feet", 0.2f,
        "Functional, low hedonic"
    ),
    PRIMARY_EROGENOUS(
        "primary_erogenous", 0.95f,
        "Rhythmic, sustained, temperature",
        isErogenous = true
    ),
    SECONDARY_EROGENOUS(
        "secondary_erogenous", 0.85f,
        "Periareolar, perineal, proximal",
        isErogenous = true
    );

    companion object {
        fun fromLabel(label: String): BodyZone? =
            entries.find { it.label == label }
    }
}
