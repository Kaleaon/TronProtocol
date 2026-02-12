package com.tronprotocol.app.frontier

/**
 * Result of an STLE accessibility computation for a single data point.
 *
 * Encodes the dual fuzzy-set representation from the Frontier Dynamics STLE framework:
 *   mu_x (accessibility) + mu_y (inaccessibility) = 1.0  (guaranteed)
 *
 * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
 */
data class AccessibilityResult(
    /** Accessibility score mu_x in [0, 1]. Higher = more familiar / in-distribution. */
    val muX: Float,
    /** Inaccessibility score mu_y in [0, 1]. Higher = more unfamiliar / out-of-distribution. */
    val muY: Float,
    /** Predicted class index (-1 if classification unavailable). */
    val prediction: Int = -1,
    /** Per-class probability distribution (softmax output). */
    val classProbabilities: FloatArray = floatArrayOf(),
    /** Epistemic uncertainty: reducible with more data. Derived from 1/(mu_x + epsilon). */
    val epistemicUncertainty: Float = 0f,
    /** Aleatoric uncertainty: inherent noise. Derived from entropy of class probabilities. */
    val aleatoricUncertainty: Float = 0f
) {
    /** The knowledge state this data point belongs to. */
    val frontierState: FrontierState
        get() = FrontierState.fromMuX(muX)

    /** Complementarity error |mu_x + mu_y - 1|. Should always be ~0. */
    val complementarityError: Float
        get() = kotlin.math.abs(muX + muY - 1.0f)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccessibilityResult) return false
        return muX == other.muX && muY == other.muY && prediction == other.prediction
    }

    override fun hashCode(): Int {
        var result = muX.hashCode()
        result = 31 * result + muY.hashCode()
        result = 31 * result + prediction
        return result
    }
}

/**
 * Three-state knowledge classification derived from mu_x.
 */
enum class FrontierState {
    /** High accessibility (mu_x > 0.8): model is confident, data is in-distribution. */
    ACCESSIBLE,
    /** Mid-range accessibility (0.2 <= mu_x <= 0.8): learning frontier — partial knowledge. */
    FRONTIER,
    /** Low accessibility (mu_x < 0.2): model has no knowledge — out-of-distribution. */
    INACCESSIBLE;

    companion object {
        private const val HIGH_THRESHOLD = 0.8f
        private const val LOW_THRESHOLD = 0.2f

        fun fromMuX(muX: Float): FrontierState = when {
            muX > HIGH_THRESHOLD -> ACCESSIBLE
            muX < LOW_THRESHOLD -> INACCESSIBLE
            else -> FRONTIER
        }
    }
}
