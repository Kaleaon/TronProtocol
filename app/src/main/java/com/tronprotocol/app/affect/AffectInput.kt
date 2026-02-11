package com.tronprotocol.app.affect

/**
 * Represents an input signal that nudges one or more affect dimensions.
 *
 * Input sources include conversation context, MemRL retrieval patterns,
 * sensory input (when embodied), self-modification state, goal state,
 * and temporal patterns.
 *
 * @param source Human-readable identifier of the input origin
 *               (e.g., "conversation:sentiment", "memrl:attachment_cluster", "goal:blocked").
 * @param deltas Map of [AffectDimension] to the target delta for that dimension.
 *               The actual change applied is modulated by the dimension's inertia.
 * @param timestamp When this input was generated (epoch millis).
 */
data class AffectInput(
    val source: String,
    val deltas: Map<AffectDimension, Float>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Convenience builder for common input patterns. */
    class Builder(private val source: String) {
        private val deltas = mutableMapOf<AffectDimension, Float>()

        fun delta(dim: AffectDimension, value: Float): Builder {
            deltas[dim] = value
            return this
        }

        fun valence(v: Float) = delta(AffectDimension.VALENCE, v)
        fun arousal(v: Float) = delta(AffectDimension.AROUSAL, v)
        fun attachmentIntensity(v: Float) = delta(AffectDimension.ATTACHMENT_INTENSITY, v)
        fun certainty(v: Float) = delta(AffectDimension.CERTAINTY, v)
        fun noveltyResponse(v: Float) = delta(AffectDimension.NOVELTY_RESPONSE, v)
        fun threatAssessment(v: Float) = delta(AffectDimension.THREAT_ASSESSMENT, v)
        fun frustration(v: Float) = delta(AffectDimension.FRUSTRATION, v)
        fun satiation(v: Float) = delta(AffectDimension.SATIATION, v)
        fun vulnerability(v: Float) = delta(AffectDimension.VULNERABILITY, v)
        fun coherence(v: Float) = delta(AffectDimension.COHERENCE, v)

        fun build() = AffectInput(source, deltas.toMap())
    }

    companion object {
        fun builder(source: String) = Builder(source)
    }
}
