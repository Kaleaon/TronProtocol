package com.tronprotocol.app.frontier

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Set Theoretic Learning Environment (STLE) — Kotlin implementation.
 *
 * Ported from the Frontier Dynamics Project (strangehospital/Frontier-Dynamics-Project).
 * Models data accessibility through complementary fuzzy sets where mu_x + mu_y = 1
 * (mathematically guaranteed). The engine teaches the system to recognise and express
 * uncertainty about unfamiliar data instead of confidently classifying everything.
 *
 * Core formula:
 *   mu_x(r) = N * P(r|accessible) / [N * P(r|accessible) + P(r|inaccessible)]
 *
 * Features:
 * - Density-based lazy initialisation (no enumeration of infinite domain)
 * - Per-class Gaussian density estimators
 * - Dirichlet concentration for aleatoric uncertainty
 * - Bayesian update mechanism for dynamic belief revision
 * - O(Cd^2) inference where C = classes, d = feature dimensions
 *
 * @param inputDim Dimensionality of input feature vectors
 * @param numClasses Number of classification classes
 *
 * @see <a href="https://github.com/strangehospital/Frontier-Dynamics-Project">Frontier Dynamics</a>
 */
class STLEEngine(
    val inputDim: Int,
    val numClasses: Int = 2
) {
    // Linear classifier weights (input_dim x num_classes)
    private var weights: Array<FloatArray> = Array(inputDim) { FloatArray(numClasses) }
    private var biases: FloatArray = FloatArray(numClasses)

    // Per-class Gaussian density parameters
    private var classMeans: Array<FloatArray> = Array(numClasses) { FloatArray(inputDim) }
    private var classCovariancesInv: Array<Array<FloatArray>> = Array(numClasses) {
        identityMatrix(inputDim)
    }
    private var classCovDets: FloatArray = FloatArray(numClasses) { 1.0f }
    private var classCounts: FloatArray = FloatArray(numClasses) { 1.0f }

    /** Whether the model has been fitted. */
    var isTrained: Boolean = false
        private set

    /** Total number of training samples seen. */
    var trainingSize: Int = 0
        private set

    init {
        // Xavier-like initialisation for weights
        val scale = 0.1f
        for (i in 0 until inputDim) {
            for (j in 0 until numClasses) {
                weights[i][j] = (Math.random().toFloat() - 0.5f) * 2 * scale
            }
        }
    }

    // ========================================================================
    // Training
    // ========================================================================

    /**
     * Train the STLE model on labelled data.
     *
     * @param X Feature matrix (n_samples x input_dim)
     * @param y Labels array (n_samples), values in [0, numClasses)
     * @param epochs Number of gradient-descent epochs
     * @param learningRate Step size for weight updates
     */
    fun fit(
        X: Array<FloatArray>,
        y: IntArray,
        epochs: Int = 100,
        learningRate: Float = 0.01f
    ) {
        require(X.isNotEmpty()) { "Training data must not be empty" }
        require(X.size == y.size) { "X and y must have the same number of samples" }
        require(X[0].size == inputDim) { "Feature dimension mismatch: expected $inputDim, got ${X[0].size}" }

        val n = X.size
        trainingSize = n

        // --- Class counts (certainty budget) --------------------------------
        classCounts = FloatArray(numClasses) { 1.0f } // Laplace smoothing
        for (label in y) {
            classCounts[label] += 1.0f
        }

        // --- Per-class statistics for density estimation --------------------
        for (c in 0 until numClasses) {
            val classIndices = y.indices.filter { y[it] == c }
            if (classIndices.isEmpty()) continue

            // Mean
            val mean = FloatArray(inputDim)
            for (idx in classIndices) {
                for (d in 0 until inputDim) {
                    mean[d] += X[idx][d]
                }
            }
            for (d in 0 until inputDim) {
                mean[d] /= classIndices.size
            }
            classMeans[c] = mean

            // Covariance (with regularisation)
            val cov = Array(inputDim) { FloatArray(inputDim) }
            for (idx in classIndices) {
                for (i in 0 until inputDim) {
                    for (j in 0 until inputDim) {
                        cov[i][j] += (X[idx][i] - mean[i]) * (X[idx][j] - mean[j])
                    }
                }
            }
            for (i in 0 until inputDim) {
                for (j in 0 until inputDim) {
                    cov[i][j] /= classIndices.size
                }
                cov[i][i] += REGULARIZATION // Ridge regularisation
            }

            // Invert covariance + compute determinant
            val invResult = invertMatrix(cov)
            classCovariancesInv[c] = invResult.first
            classCovDets[c] = invResult.second
        }

        // --- Gradient descent on linear classifier --------------------------
        for (epoch in 0 until epochs) {
            val logits = Array(n) { FloatArray(numClasses) }
            for (i in 0 until n) {
                for (j in 0 until numClasses) {
                    var sum = biases[j]
                    for (d in 0 until inputDim) {
                        sum += X[i][d] * weights[d][j]
                    }
                    logits[i][j] = sum
                }
            }

            // Softmax probabilities
            val probs = Array(n) { softmax(logits[it]) }

            // Gradients
            val gradLogits = Array(n) { i ->
                val g = probs[i].copyOf()
                g[y[i]] -= 1.0f
                for (j in g.indices) g[j] /= n
                g
            }

            // Update weights and biases
            for (d in 0 until inputDim) {
                for (j in 0 until numClasses) {
                    var grad = 0f
                    for (i in 0 until n) {
                        grad += X[i][d] * gradLogits[i][j]
                    }
                    weights[d][j] -= learningRate * grad
                }
            }
            for (j in 0 until numClasses) {
                var grad = 0f
                for (i in 0 until n) {
                    grad += gradLogits[i][j]
                }
                biases[j] -= learningRate * grad
            }
        }

        isTrained = true
        Log.d(TAG, "STLE trained on $n samples, $numClasses classes, $inputDim dims")
    }

    // ========================================================================
    // Inference
    // ========================================================================

    /**
     * Compute accessibility mu_x for a single feature vector.
     *
     * This is the **core** of STLE:
     *   mu_x(r) = N * P(r|accessible) / [N * P(r|accessible) + P(r|inaccessible)]
     */
    fun computeMuX(x: FloatArray): Float {
        check(isTrained) { "Model must be trained before computing mu_x" }
        require(x.size == inputDim) { "Feature dimension mismatch" }

        // Compute Gaussian density for each class
        val densities = FloatArray(numClasses) { c ->
            gaussianDensity(x, classMeans[c], classCovariancesInv[c], classCovDets[c])
        }

        // Pseudo-counts: beta = N_c * P(x | class_c)
        val beta = FloatArray(numClasses) { c -> classCounts[c] * densities[c] }

        // Dirichlet concentration: alpha = beta_prior + beta
        val alpha = FloatArray(numClasses) { c -> BETA_PRIOR + beta[c] }
        val alpha0 = alpha.sum()

        // Accessibility: mu_x = max(alpha) / alpha_0
        return if (alpha0 > 0f) alpha.max() / alpha0 else 0.5f
    }

    /**
     * Compute full accessibility result for a batch of feature vectors.
     */
    fun predict(X: Array<FloatArray>): List<AccessibilityResult> {
        check(isTrained) { "Model must be trained before prediction" }
        return X.map { predictSingle(it) }
    }

    /**
     * Compute full accessibility result for a single feature vector.
     */
    fun predictSingle(x: FloatArray): AccessibilityResult {
        check(isTrained) { "Model must be trained before prediction" }
        require(x.size == inputDim) { "Feature dimension mismatch" }

        // Classification logits and softmax
        val logits = FloatArray(numClasses) { j ->
            var sum = biases[j]
            for (d in 0 until inputDim) {
                sum += x[d] * weights[d][j]
            }
            sum
        }
        val probs = softmax(logits)
        val prediction = probs.indices.maxByOrNull { probs[it] } ?: 0

        // Accessibility
        val muX = computeMuX(x)
        val muY = 1.0f - muX

        // Epistemic uncertainty (inverse of confidence)
        val epistemic = 1.0f / (muX + 0.1f)

        // Aleatoric uncertainty (entropy of class distribution)
        var entropy = 0f
        for (p in probs) {
            if (p > 1e-10f) {
                entropy -= p * ln(p)
            }
        }

        return AccessibilityResult(
            muX = muX,
            muY = muY,
            prediction = prediction,
            classProbabilities = probs,
            epistemicUncertainty = epistemic,
            aleatoricUncertainty = entropy
        )
    }

    // ========================================================================
    // Bayesian Update
    // ========================================================================

    /**
     * Bayesian update of mu_x given new evidence.
     *
     * mu_x_updated = L(E|accessible) * mu_x / [L(E|accessible) * mu_x + L(E|inacc) * mu_y]
     *
     * This preserves complementarity: mu_x + mu_y = 1 after update.
     *
     * @param currentMuX Current accessibility score
     * @param likelihoodAccessible P(evidence | data is accessible)
     * @param likelihoodInaccessible P(evidence | data is inaccessible)
     * @return Updated mu_x after incorporating evidence
     */
    fun bayesianUpdate(
        currentMuX: Float,
        likelihoodAccessible: Float,
        likelihoodInaccessible: Float
    ): Float {
        val numerator = likelihoodAccessible * currentMuX
        val denominator = numerator + likelihoodInaccessible * (1.0f - currentMuX)
        return if (denominator > 0f) numerator / denominator else currentMuX
    }

    // ========================================================================
    // OOD Detection Utilities
    // ========================================================================

    /**
     * Compute AUROC for out-of-distribution detection.
     *
     * @param idScores mu_x scores for in-distribution samples
     * @param oodScores mu_x scores for out-of-distribution samples
     * @return AUROC value in [0, 1]; higher means better OOD detection
     */
    fun computeAUROC(idScores: FloatArray, oodScores: FloatArray): Float {
        val scores = FloatArray(idScores.size + oodScores.size)
        val labels = IntArray(idScores.size + oodScores.size)

        System.arraycopy(idScores, 0, scores, 0, idScores.size)
        System.arraycopy(oodScores, 0, scores, idScores.size, oodScores.size)
        for (i in idScores.indices) labels[i] = 1
        // OOD labels remain 0

        // Sort by scores descending
        val indices = (scores.indices).sortedByDescending { scores[it] }

        val nPos = idScores.size.toFloat()
        val nNeg = oodScores.size.toFloat()
        if (nPos == 0f || nNeg == 0f) return 0.5f

        var tpCount = 0f
        var fpCount = 0f
        var prevFpr = 0f
        var prevTpr = 0f
        var auroc = 0f

        for (idx in indices) {
            if (labels[idx] == 1) tpCount++ else fpCount++
            val tpr = tpCount / nPos
            val fpr = fpCount / nNeg
            // Trapezoidal rule
            auroc += (fpr - prevFpr) * (tpr + prevTpr) / 2f
            prevFpr = fpr
            prevTpr = tpr
        }

        return auroc
    }

    /**
     * Classify samples into three knowledge states: ACCESSIBLE, FRONTIER, INACCESSIBLE.
     */
    fun classifyFrontier(results: List<AccessibilityResult>): FrontierDistribution {
        var accessible = 0
        var frontier = 0
        var inaccessible = 0

        for (r in results) {
            when (r.frontierState) {
                FrontierState.ACCESSIBLE -> accessible++
                FrontierState.FRONTIER -> frontier++
                FrontierState.INACCESSIBLE -> inaccessible++
            }
        }

        return FrontierDistribution(
            total = results.size,
            accessible = accessible,
            frontier = frontier,
            inaccessible = inaccessible,
            meanMuX = results.map { it.muX }.average().toFloat(),
            meanEpistemic = results.map { it.epistemicUncertainty }.average().toFloat()
        )
    }

    // ========================================================================
    // Internal math helpers
    // ========================================================================

    private fun softmax(logits: FloatArray): FloatArray {
        val maxVal = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - maxVal) }
        val sum = exps.sum()
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    /**
     * Compute multivariate Gaussian density.
     */
    private fun gaussianDensity(
        x: FloatArray,
        mean: FloatArray,
        covInv: Array<FloatArray>,
        covDet: Float
    ): Float {
        val d = mean.size
        val centered = FloatArray(d) { x[it] - mean[it] }

        // Mahalanobis distance: (x - mu)^T * Sigma^{-1} * (x - mu)
        var mahalanobis = 0f
        for (i in 0 until d) {
            var rowSum = 0f
            for (j in 0 until d) {
                rowSum += covInv[i][j] * centered[j]
            }
            mahalanobis += centered[i] * rowSum
        }

        val logNorm = -0.5f * (d * LN_2PI + ln(abs(covDet) + 1e-30f))
        val logDensity = logNorm - 0.5f * mahalanobis

        // Clamp to prevent underflow/overflow
        return exp(logDensity.coerceIn(-80f, 80f))
    }

    /**
     * Invert a square matrix and compute its determinant.
     * Uses Gaussian elimination with partial pivoting.
     * Falls back to diagonal inversion on failure.
     *
     * @return Pair of (inverse matrix, determinant)
     */
    private fun invertMatrix(matrix: Array<FloatArray>): Pair<Array<FloatArray>, Float> {
        val n = matrix.size
        // Augmented matrix [A | I]
        val aug = Array(n) { i ->
            FloatArray(2 * n) { j ->
                if (j < n) matrix[i][j] else if (j - n == i) 1.0f else 0.0f
            }
        }

        var det = 1.0f

        for (col in 0 until n) {
            // Partial pivoting
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > maxVal) {
                    maxVal = abs(aug[row][col])
                    maxRow = row
                }
            }
            if (maxRow != col) {
                val tmp = aug[col]
                aug[col] = aug[maxRow]
                aug[maxRow] = tmp
                det *= -1
            }

            val pivot = aug[col][col]
            if (abs(pivot) < 1e-10f) {
                // Singular — fall back to diagonal
                return diagonalInverse(matrix)
            }

            det *= pivot

            for (j in 0 until 2 * n) {
                aug[col][j] /= pivot
            }

            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                for (j in 0 until 2 * n) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        val inverse = Array(n) { i -> FloatArray(n) { j -> aug[i][j + n] } }
        return Pair(inverse, det)
    }

    private fun diagonalInverse(matrix: Array<FloatArray>): Pair<Array<FloatArray>, Float> {
        val n = matrix.size
        val inv = identityMatrix(n)
        var det = 1.0f
        for (i in 0 until n) {
            val d = matrix[i][i] + REGULARIZATION
            inv[i][i] = 1.0f / d
            det *= d
        }
        return Pair(inv, det)
    }

    companion object {
        private const val TAG = "STLEEngine"
        private const val BETA_PRIOR = 1.0f // Flat Dirichlet prior
        private const val REGULARIZATION = 0.01f
        private val LN_2PI = ln(2.0f * Math.PI.toFloat())

        private fun identityMatrix(n: Int): Array<FloatArray> =
            Array(n) { i -> FloatArray(n) { j -> if (i == j) 1.0f else 0.0f } }

        /**
         * Compute AUROC for out-of-distribution detection (static utility).
         *
         * @param idScores mu_x scores for in-distribution samples
         * @param oodScores mu_x scores for out-of-distribution samples
         * @return AUROC value in [0, 1]; higher means better OOD detection
         */
        @JvmStatic
        fun computeAUROCStatic(idScores: FloatArray, oodScores: FloatArray): Float {
            val scores = FloatArray(idScores.size + oodScores.size)
            val labels = IntArray(idScores.size + oodScores.size)

            System.arraycopy(idScores, 0, scores, 0, idScores.size)
            System.arraycopy(oodScores, 0, scores, idScores.size, oodScores.size)
            for (i in idScores.indices) labels[i] = 1

            val indices = (scores.indices).sortedByDescending { scores[it] }

            val nPos = idScores.size.toFloat()
            val nNeg = oodScores.size.toFloat()
            if (nPos == 0f || nNeg == 0f) return 0.5f

            var tpCount = 0f
            var fpCount = 0f
            var prevFpr = 0f
            var prevTpr = 0f
            var auroc = 0f

            for (idx in indices) {
                if (labels[idx] == 1) tpCount++ else fpCount++
                val tpr = tpCount / nPos
                val fpr = fpCount / nNeg
                auroc += (fpr - prevFpr) * (tpr + prevTpr) / 2f
                prevFpr = fpr
                prevTpr = tpr
            }

            return auroc
        }
    }
}

/**
 * Distribution of samples across the three STLE knowledge states.
 */
data class FrontierDistribution(
    val total: Int,
    val accessible: Int,
    val frontier: Int,
    val inaccessible: Int,
    val meanMuX: Float,
    val meanEpistemic: Float
) {
    val accessiblePct: Float get() = if (total > 0) accessible.toFloat() / total * 100 else 0f
    val frontierPct: Float get() = if (total > 0) frontier.toFloat() / total * 100 else 0f
    val inaccessiblePct: Float get() = if (total > 0) inaccessible.toFloat() / total * 100 else 0f
}
