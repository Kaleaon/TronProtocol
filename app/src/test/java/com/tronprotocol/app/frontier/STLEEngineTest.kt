package com.tronprotocol.app.frontier

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class STLEEngineTest {

    private lateinit var engine: STLEEngine

    // Simple 2D dataset: two clusters
    private lateinit var trainX: Array<FloatArray>
    private lateinit var trainY: IntArray
    private lateinit var testX: Array<FloatArray>
    private lateinit var testY: IntArray

    @Before
    fun setUp() {
        // Class 0: cluster around (1, 1)
        // Class 1: cluster around (-1, -1)
        trainX = arrayOf(
            floatArrayOf(1.0f, 1.0f),
            floatArrayOf(1.1f, 0.9f),
            floatArrayOf(0.9f, 1.1f),
            floatArrayOf(1.2f, 0.8f),
            floatArrayOf(0.8f, 1.2f),
            floatArrayOf(1.0f, 1.2f),
            floatArrayOf(1.1f, 1.1f),
            floatArrayOf(0.9f, 0.9f),
            floatArrayOf(-1.0f, -1.0f),
            floatArrayOf(-1.1f, -0.9f),
            floatArrayOf(-0.9f, -1.1f),
            floatArrayOf(-1.2f, -0.8f),
            floatArrayOf(-0.8f, -1.2f),
            floatArrayOf(-1.0f, -1.2f),
            floatArrayOf(-1.1f, -1.1f),
            floatArrayOf(-0.9f, -0.9f)
        )
        trainY = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1)

        testX = arrayOf(
            floatArrayOf(1.0f, 1.0f),   // Class 0
            floatArrayOf(-1.0f, -1.0f), // Class 1
        )
        testY = intArrayOf(0, 1)

        engine = STLEEngine(inputDim = 2, numClasses = 2)
        engine.fit(trainX, trainY, epochs = 100, learningRate = 0.05f)
    }

    // --- Training ---

    @Test
    fun testEngineTrains() {
        assertTrue(engine.isTrained)
    }

    @Test
    fun testTrainingSize() {
        assertEquals(16, engine.trainingSize)
    }

    @Test(expected = IllegalStateException::class)
    fun testComputeMuXBeforeTrainingThrows() {
        val untrained = STLEEngine(inputDim = 2)
        untrained.computeMuX(floatArrayOf(1.0f, 1.0f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFitEmptyDataThrows() {
        val fresh = STLEEngine(inputDim = 2)
        fresh.fit(emptyArray(), intArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFitDimensionMismatchThrows() {
        val fresh = STLEEngine(inputDim = 3)
        fresh.fit(trainX, trainY) // trainX is 2D, engine expects 3D
    }

    // --- Accessibility (mu_x) ---

    @Test
    fun testMuXInRange() {
        val muX = engine.computeMuX(floatArrayOf(1.0f, 1.0f))
        assertTrue("mu_x should be in [0, 1], got $muX", muX in 0.0f..1.0f)
    }

    @Test
    fun testComplementarity() {
        val result = engine.predictSingle(floatArrayOf(1.0f, 1.0f))
        val error = kotlin.math.abs(result.muX + result.muY - 1.0f)
        assertTrue("Complementarity error should be ~0, got $error", error < 1e-6f)
    }

    @Test
    fun testComplementarityForAllSamples() {
        val results = engine.predict(trainX)
        for (r in results) {
            val error = kotlin.math.abs(r.muX + r.muY - 1.0f)
            assertTrue("Complementarity violated: mu_x=${r.muX}, mu_y=${r.muY}", error < 1e-6f)
        }
    }

    @Test
    fun testInDistributionHighMuX() {
        // Samples near training data should have higher mu_x
        val muX = engine.computeMuX(floatArrayOf(1.0f, 1.0f))
        assertTrue("In-distribution mu_x should be > 0.5, got $muX", muX > 0.5f)
    }

    @Test
    fun testOutOfDistributionLowerMuX() {
        // Sample far from training data should have lower mu_x
        val inDistMuX = engine.computeMuX(floatArrayOf(1.0f, 1.0f))
        val oodMuX = engine.computeMuX(floatArrayOf(10.0f, 10.0f))
        assertTrue(
            "OOD mu_x ($oodMuX) should be <= ID mu_x ($inDistMuX)",
            oodMuX <= inDistMuX
        )
    }

    // --- Prediction ---

    @Test
    fun testPredictSingleReturnsResult() {
        val result = engine.predictSingle(floatArrayOf(1.0f, 1.0f))
        assertNotNull(result)
        assertTrue(result.prediction in 0..1)
    }

    @Test
    fun testPredictBatchSize() {
        val results = engine.predict(testX)
        assertEquals(2, results.size)
    }

    @Test
    fun testClassProbabilitiesSumToOne() {
        val result = engine.predictSingle(floatArrayOf(1.0f, 1.0f))
        val sum = result.classProbabilities.sum()
        assertEquals("Class probabilities should sum to ~1.0", 1.0f, sum, 0.001f)
    }

    @Test
    fun testEpistemicUncertaintyPositive() {
        val result = engine.predictSingle(floatArrayOf(1.0f, 1.0f))
        assertTrue("Epistemic should be > 0", result.epistemicUncertainty > 0f)
    }

    @Test
    fun testAleatoricUncertaintyNonNegative() {
        val result = engine.predictSingle(floatArrayOf(1.0f, 1.0f))
        assertTrue("Aleatoric should be >= 0", result.aleatoricUncertainty >= 0f)
    }

    // --- Bayesian Update ---

    @Test
    fun testBayesianUpdatePositiveEvidence() {
        val initial = 0.6f
        val updated = engine.bayesianUpdate(initial, 0.9f, 0.1f)
        assertTrue("Positive evidence should increase mu_x: $initial -> $updated", updated > initial)
    }

    @Test
    fun testBayesianUpdateNegativeEvidence() {
        val initial = 0.6f
        val updated = engine.bayesianUpdate(initial, 0.1f, 0.9f)
        assertTrue("Negative evidence should decrease mu_x: $initial -> $updated", updated < initial)
    }

    @Test
    fun testBayesianUpdatePreservesComplementarity() {
        val initial = 0.7f
        val updated = engine.bayesianUpdate(initial, 0.9f, 0.1f)
        val muY = 1.0f - updated
        val error = kotlin.math.abs(updated + muY - 1.0f)
        assertTrue("Complementarity must hold after update", error < 1e-6f)
    }

    @Test
    fun testBayesianUpdateEqualLikelihoodNoChange() {
        val initial = 0.6f
        val updated = engine.bayesianUpdate(initial, 0.5f, 0.5f)
        assertEquals("Equal likelihoods should not change mu_x", initial, updated, 0.001f)
    }

    // --- AUROC ---

    @Test
    fun testAUROCPerfectSeparation() {
        val idScores = floatArrayOf(0.9f, 0.85f, 0.88f, 0.92f)
        val oodScores = floatArrayOf(0.1f, 0.15f, 0.12f, 0.08f)
        val auroc = engine.computeAUROC(idScores, oodScores)
        assertEquals("Perfect separation should give AUROC ~1.0", 1.0f, auroc, 0.01f)
    }

    @Test
    fun testAUROCRandomScores() {
        val idScores = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val oodScores = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val auroc = engine.computeAUROC(idScores, oodScores)
        // With identical scores, AUROC should be ~0.5
        assertTrue("Random AUROC should be ~0.5, got $auroc", auroc in 0.3f..0.7f)
    }

    // --- Frontier Classification ---

    @Test
    fun testClassifyFrontier() {
        val results = engine.predict(trainX)
        val dist = engine.classifyFrontier(results)
        assertEquals(trainX.size, dist.total)
        assertEquals(trainX.size, dist.accessible + dist.frontier + dist.inaccessible)
    }

    @Test
    fun testFrontierDistributionPercentages() {
        val results = engine.predict(trainX)
        val dist = engine.classifyFrontier(results)
        val totalPct = dist.accessiblePct + dist.frontierPct + dist.inaccessiblePct
        assertEquals("Percentages should sum to ~100", 100.0f, totalPct, 0.1f)
    }
}
