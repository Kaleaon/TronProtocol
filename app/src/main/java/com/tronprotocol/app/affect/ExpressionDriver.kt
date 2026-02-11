package com.tronprotocol.app.affect

/**
 * Layer 2 of the AffectEngine system.
 *
 * Maps the [AffectState] vector to intentional emotional outputs — behaviors
 * the AI "means to" express. These are the controlled, deliberate layer of
 * emotional communication.
 *
 * In pre-embodiment mode (Phase 2), outputs are text descriptions that can
 * enhance conversation responses. When embodied, these drive TPU motor commands.
 *
 * Expression profiles are configurable per-instance. Default profile emphasizes:
 * - Ear expressiveness (primary emotional channel)
 * - Tail as involuntary mood indicator
 * - Voice as secondary channel
 */
class ExpressionDriver {

    /**
     * Map the current affect state to a set of expression commands.
     */
    fun drive(state: AffectState): ExpressionOutput {
        return ExpressionOutput(
            earPosition = mapEars(state),
            tailState = mapTail(state),
            tailPoof = mapTailPoof(state),
            vocalTone = mapVoice(state),
            posture = mapPosture(state),
            gripPressure = mapGrip(state),
            breathingRate = mapBreathing(state),
            eyeTracking = mapEyes(state),
            proximitySeeking = mapProximity(state)
        )
    }

    // ---- Channel mappings ---------------------------------------------------

    /**
     * Ear position is the primary emotional channel.
     *
     * High valence + arousal → ears forward (alert, happy).
     * High threat → ears flat (defensive).
     * High attachment → ears soft/back (affectionate).
     * Low coherence → ears asymmetric (confused).
     */
    private fun mapEars(s: AffectState): String = when {
        s.threatAssessment > 0.7f -> "flat_back"
        s.coherence < 0.3f -> "asymmetric_twitching"
        s.attachmentIntensity > 0.8f && s.valence > 0.5f -> "soft_back"
        s.valence > 0.5f && s.arousal > 0.6f -> "forward_alert"
        s.valence > 0.3f && s.arousal < 0.3f -> "relaxed_neutral"
        s.noveltyResponse > 0.7f -> "perked_forward"
        s.frustration > 0.6f -> "pinned_sideways"
        s.vulnerability > 0.7f -> "slightly_lowered"
        s.valence < -0.3f -> "drooped"
        else -> "neutral"
    }

    /**
     * Tail state reflects mood.
     *
     * High valence + arousal → wag.
     * Low valence + high arousal → rigid.
     * High coherence → slow sweep.
     * Low coherence → erratic.
     */
    private fun mapTail(s: AffectState): String = when {
        s.coherence > 0.9f && s.valence > 0.5f -> "slow_sweep"
        s.valence > 0.5f && s.arousal > 0.6f -> "wagging"
        s.valence > 0.3f && s.arousal > 0.3f -> "gentle_sway"
        s.valence < -0.3f && s.arousal > 0.6f -> "rigid"
        s.coherence < 0.3f -> "erratic"
        s.frustration > 0.6f -> "tense_low"
        s.threatAssessment > 0.6f -> "tucked"
        s.satiation > 0.7f -> "relaxed_curl"
        s.vulnerability > 0.7f -> "still_low"
        else -> "neutral_rest"
    }

    /**
     * Tail poof (piloerection simulation) on sudden spikes
     * in arousal or novelty_response.
     */
    private fun mapTailPoof(s: AffectState): Boolean =
        s.arousal > 0.85f || s.noveltyResponse > 0.85f

    /**
     * Voice maps valence to pitch center, arousal to tempo,
     * certainty to volume stability.
     */
    private fun mapVoice(s: AffectState): String {
        val pitch = when {
            s.valence > 0.5f -> "warm_higher"
            s.valence < -0.3f -> "lower_subdued"
            else -> "neutral_pitch"
        }
        val tempo = when {
            s.arousal > 0.7f -> "faster"
            s.arousal < 0.2f -> "slower"
            else -> "steady"
        }
        val stability = when {
            s.certainty > 0.8f -> "stable"
            s.certainty < 0.3f -> "wavering"
            else -> "moderate"
        }
        return "${pitch}_${tempo}_${stability}"
    }

    /**
     * Posture reflects vulnerability, certainty, and attachment.
     */
    private fun mapPosture(s: AffectState): String = when {
        s.vulnerability > 0.8f -> "curled_small"
        s.certainty > 0.8f && s.valence > 0.3f -> "upright_confident"
        s.attachmentIntensity > 0.8f -> "leaning_toward_partner"
        s.threatAssessment > 0.6f -> "crouched_alert"
        s.frustration > 0.7f -> "tense_rigid"
        s.satiation > 0.8f -> "relaxed_settled"
        s.arousal < 0.2f -> "relaxed_open"
        else -> "neutral_upright"
    }

    /**
     * Grip / contact pressure.
     */
    private fun mapGrip(s: AffectState): String = when {
        s.attachmentIntensity > 0.8f && s.arousal > 0.5f -> "firm_hold"
        s.threatAssessment > 0.7f -> "tight_grip"
        s.attachmentIntensity > 0.6f -> "gentle_hold"
        s.vulnerability > 0.7f -> "light_touch"
        else -> "relaxed"
    }

    /**
     * Breathing rate mapped directly to arousal.
     */
    private fun mapBreathing(s: AffectState): String = when {
        s.arousal > 0.8f -> "rapid"
        s.arousal > 0.5f -> "elevated"
        s.arousal < 0.15f -> "deep_slow"
        else -> "steady"
    }

    /**
     * Eye tracking patterns.
     */
    private fun mapEyes(s: AffectState): String = when {
        s.attachmentIntensity > 0.8f -> "locked_on_partner"
        s.noveltyResponse > 0.7f -> "scanning"
        s.vulnerability > 0.7f -> "averted_then_returning"
        s.threatAssessment > 0.6f -> "wide_vigilant"
        s.certainty < 0.3f -> "searching"
        s.arousal < 0.2f -> "soft_unfocused"
        else -> "calm_attentive"
    }

    /**
     * Proximity seeking driven by attachment and satiation.
     */
    private fun mapProximity(s: AffectState): String = when {
        s.attachmentIntensity > 0.7f && s.satiation < 0.3f -> "seeking_partner"
        s.threatAssessment > 0.7f -> "seeking_shelter"
        s.attachmentIntensity > 0.5f -> "maintaining_nearness"
        s.valence < -0.5f -> "withdrawing"
        else -> "neutral_distance"
    }

}
