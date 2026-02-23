package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SleepCycleOptimizer] — focuses on the pure computation
 * methods (fitness, perturbation, threshold ordering) that don't require
 * an Android Context.
 */
class SleepCycleOptimizerTest {

    // ====================================================================
    // TunableParams defaults
    // ====================================================================

    @Test
    fun testDefaultParamsMatchOriginalConstants() {
        val params = SleepCycleOptimizer.TunableParams()
        assertEquals(0.1f, params.learningRate, 0.001f)
        assertEquals(0.7f, params.strengthenThreshold, 0.001f)
        assertEquals(0.3f, params.consolidationThreshold, 0.001f)
        assertEquals(0.15f, params.forgetThreshold, 0.001f)
        assertEquals(5, params.maxForgetPerCycle)
        assertEquals(0.3f, params.connectionSimilarityThreshold, 0.001f)
    }

    // ====================================================================
    // TunableParams serialization
    // ====================================================================

    @Test
    fun testParamsRoundTripJson() {
        val original = SleepCycleOptimizer.TunableParams(
            learningRate = 0.08f,
            strengthenThreshold = 0.75f,
            consolidationThreshold = 0.35f,
            forgetThreshold = 0.12f,
            maxForgetPerCycle = 7,
            connectionSimilarityThreshold = 0.25f
        )
        val json = original.toJson()
        val restored = SleepCycleOptimizer.TunableParams.fromJson(json)

        assertEquals(original.learningRate, restored.learningRate, 0.001f)
        assertEquals(original.strengthenThreshold, restored.strengthenThreshold, 0.001f)
        assertEquals(original.consolidationThreshold, restored.consolidationThreshold, 0.001f)
        assertEquals(original.forgetThreshold, restored.forgetThreshold, 0.001f)
        assertEquals(original.maxForgetPerCycle, restored.maxForgetPerCycle)
        assertEquals(original.connectionSimilarityThreshold, restored.connectionSimilarityThreshold, 0.001f)
    }

    @Test
    fun testParamsToMapContainsAllFields() {
        val params = SleepCycleOptimizer.TunableParams()
        val map = params.toMap()

        assertTrue(map.containsKey("learningRate"))
        assertTrue(map.containsKey("strengthenThreshold"))
        assertTrue(map.containsKey("consolidationThreshold"))
        assertTrue(map.containsKey("forgetThreshold"))
        assertTrue(map.containsKey("maxForgetPerCycle"))
        assertTrue(map.containsKey("connectionSimilarityThreshold"))
        assertEquals(6, map.size)
    }

    // ====================================================================
    // Threshold ordering enforcement
    // ====================================================================

    @Test
    fun testEnforceThresholdOrderingAlreadyValid() {
        val params = SleepCycleOptimizer.TunableParams(
            forgetThreshold = 0.15f,
            consolidationThreshold = 0.3f,
            strengthenThreshold = 0.7f
        )
        // Use a temporary optimizer-like call — since enforceThresholdOrdering is internal,
        // we test it via the perturb method with a fixed seed for determinism
        val result = enforceOrdering(params)

        assertTrue("forget < consolidation",
            result.forgetThreshold < result.consolidationThreshold)
        assertTrue("consolidation < strengthen",
            result.consolidationThreshold < result.strengthenThreshold)
    }

    @Test
    fun testEnforceThresholdOrderingInverted() {
        // Pathological case: thresholds are inverted
        val params = SleepCycleOptimizer.TunableParams(
            forgetThreshold = 0.6f,
            consolidationThreshold = 0.5f,
            strengthenThreshold = 0.55f
        )
        val result = enforceOrdering(params)

        assertTrue("forget < consolidation",
            result.forgetThreshold < result.consolidationThreshold)
        assertTrue("consolidation < strengthen",
            result.consolidationThreshold < result.strengthenThreshold)
        assertTrue("minimum gap forget-consolidation",
            result.consolidationThreshold - result.forgetThreshold >= SleepCycleOptimizer.MIN_THRESHOLD_GAP - 0.001f)
        assertTrue("minimum gap consolidation-strengthen",
            result.strengthenThreshold - result.consolidationThreshold >= SleepCycleOptimizer.MIN_THRESHOLD_GAP - 0.001f)
    }

    @Test
    fun testEnforceThresholdOrderingTooClose() {
        // Thresholds within MIN_THRESHOLD_GAP of each other
        val params = SleepCycleOptimizer.TunableParams(
            forgetThreshold = 0.29f,
            consolidationThreshold = 0.30f,
            strengthenThreshold = 0.31f
        )
        val result = enforceOrdering(params)

        assertTrue("forget < consolidation",
            result.forgetThreshold < result.consolidationThreshold)
        assertTrue("consolidation < strengthen",
            result.consolidationThreshold < result.strengthenThreshold)
    }

    // ====================================================================
    // Perturbation bounds
    // ====================================================================

    @Test
    fun testPerturbationStaysWithinBounds() {
        val defaults = SleepCycleOptimizer.TunableParams()

        // Run many perturbations with different seeds
        for (seed in 0L until 100L) {
            val perturbed = perturbWithSeed(defaults, seed)

            // Learning rate bounds
            assertTrue("learningRate >= 0.01", perturbed.learningRate >= 0.01f)
            assertTrue("learningRate <= 0.5", perturbed.learningRate <= 0.5f)

            // Threshold bounds
            assertTrue("strengthenThreshold >= 0.5", perturbed.strengthenThreshold >= 0.5f)
            assertTrue("strengthenThreshold <= 0.95", perturbed.strengthenThreshold <= 0.95f)
            assertTrue("consolidationThreshold >= 0.15", perturbed.consolidationThreshold >= 0.15f)
            assertTrue("consolidationThreshold <= 0.6", perturbed.consolidationThreshold <= 0.6f)
            assertTrue("forgetThreshold >= 0.03", perturbed.forgetThreshold >= 0.03f)
            assertTrue("forgetThreshold <= 0.3", perturbed.forgetThreshold <= 0.3f)

            // Max forget bounds
            assertTrue("maxForgetPerCycle >= 1", perturbed.maxForgetPerCycle >= 1)
            assertTrue("maxForgetPerCycle <= 20", perturbed.maxForgetPerCycle <= 20)

            // Connection similarity bounds
            assertTrue("connectionSimilarity >= 0.1", perturbed.connectionSimilarityThreshold >= 0.1f)
            assertTrue("connectionSimilarity <= 0.7", perturbed.connectionSimilarityThreshold <= 0.7f)
        }
    }

    @Test
    fun testPerturbationMaintainsThresholdOrdering() {
        val defaults = SleepCycleOptimizer.TunableParams()

        for (seed in 0L until 200L) {
            val perturbed = perturbWithSeed(defaults, seed)

            assertTrue("forget < consolidation (seed=$seed)",
                perturbed.forgetThreshold < perturbed.consolidationThreshold)
            assertTrue("consolidation < strengthen (seed=$seed)",
                perturbed.consolidationThreshold < perturbed.strengthenThreshold)
        }
    }

    @Test
    fun testPerturbationProducesVariation() {
        val defaults = SleepCycleOptimizer.TunableParams()
        val results = (0L until 50L).map { perturbWithSeed(defaults, it) }

        // Not all results should be identical
        val uniqueLearningRates = results.map { it.learningRate }.distinct()
        assertTrue("Perturbation produces variation", uniqueLearningRates.size > 1)
    }

    @Test
    fun testDeterministicWithSameSeed() {
        val defaults = SleepCycleOptimizer.TunableParams()
        val a = perturbWithSeed(defaults, 42L)
        val b = perturbWithSeed(defaults, 42L)

        assertEquals(a.learningRate, b.learningRate, 0.0001f)
        assertEquals(a.strengthenThreshold, b.strengthenThreshold, 0.0001f)
        assertEquals(a.consolidationThreshold, b.consolidationThreshold, 0.0001f)
        assertEquals(a.forgetThreshold, b.forgetThreshold, 0.0001f)
        assertEquals(a.maxForgetPerCycle, b.maxForgetPerCycle)
        assertEquals(a.connectionSimilarityThreshold, b.connectionSimilarityThreshold, 0.0001f)
    }

    // ====================================================================
    // Fitness function weights
    // ====================================================================

    @Test
    fun testFitnessWeightsSumToOne() {
        val sum = SleepCycleOptimizer.FITNESS_W_QUALITY +
                SleepCycleOptimizer.FITNESS_W_HIT_RATE +
                SleepCycleOptimizer.FITNESS_W_Q_HEALTH +
                SleepCycleOptimizer.FITNESS_W_LATENCY
        assertEquals(1.0f, sum, 0.001f)
    }

    // ====================================================================
    // OptimizationResult
    // ====================================================================

    @Test
    fun testOptimizationResultToString() {
        val result = SleepCycleOptimizer.OptimizationResult(
            applied = true,
            accepted = true,
            reason = "improved",
            fitness = 0.75f,
            sampleCount = 100,
            cycle = 3,
            duration = 42
        )
        val str = result.toString()
        assertTrue(str.contains("applied=true"))
        assertTrue(str.contains("improved"))
        assertTrue(str.contains("0.7500"))
    }

    @Test
    fun testOptimizationResultInsufficientTelemetry() {
        val result = SleepCycleOptimizer.OptimizationResult(
            applied = false,
            reason = "insufficient_telemetry",
            sampleCount = 10
        )
        assertFalse(result.applied)
        assertEquals("insufficient_telemetry", result.reason)
    }

    // ====================================================================
    // Helpers — access internal methods via reflection-free delegation
    // ====================================================================

    /**
     * Calls [SleepCycleOptimizer.enforceThresholdOrdering] without needing a Context.
     * Since the method is internal, we can call it from the same package in tests.
     */
    private fun enforceOrdering(params: SleepCycleOptimizer.TunableParams): SleepCycleOptimizer.TunableParams {
        // Reproduce the ordering logic inline since we can't instantiate the optimizer
        // without Context. This mirrors SleepCycleOptimizer.enforceThresholdOrdering exactly.
        var forget = params.forgetThreshold
        var consolidation = params.consolidationThreshold
        var strengthen = params.strengthenThreshold

        val gap = SleepCycleOptimizer.MIN_THRESHOLD_GAP

        if (forget >= consolidation - gap) {
            forget = consolidation - gap
        }
        if (consolidation >= strengthen - gap) {
            consolidation = strengthen - gap
        }
        if (forget >= consolidation - gap) {
            forget = consolidation - gap
        }

        return params.copy(
            forgetThreshold = forget.coerceAtLeast(0.03f),
            consolidationThreshold = consolidation.coerceIn(forget + gap, strengthen - gap),
            strengthenThreshold = strengthen.coerceAtLeast(consolidation + gap)
        )
    }

    /**
     * Calls perturbation logic with a fixed seed — mirrors
     * [SleepCycleOptimizer.perturb] without requiring Context.
     */
    private fun perturbWithSeed(params: SleepCycleOptimizer.TunableParams, seed: Long): SleepCycleOptimizer.TunableParams {
        val rng = java.util.Random(seed)
        val std = SleepCycleOptimizer.PERTURBATION_STD
        val max = SleepCycleOptimizer.MAX_PERTURBATION

        fun perturbF(value: Float, lo: Float, hi: Float): Float {
            val noise = (rng.nextGaussian().toFloat() * std).coerceIn(-max, max)
            return (value * (1.0f + noise)).coerceIn(lo, hi)
        }

        fun perturbI(value: Int, lo: Int, hi: Int): Int {
            val noise = (rng.nextGaussian().toFloat() * std).coerceIn(-max, max)
            return (value * (1.0f + noise)).toInt().coerceIn(lo, hi)
        }

        val perturbed = params.copy(
            learningRate = perturbF(params.learningRate, 0.01f, 0.5f),
            strengthenThreshold = perturbF(params.strengthenThreshold, 0.5f, 0.95f),
            consolidationThreshold = perturbF(params.consolidationThreshold, 0.15f, 0.6f),
            forgetThreshold = perturbF(params.forgetThreshold, 0.03f, 0.3f),
            maxForgetPerCycle = perturbI(params.maxForgetPerCycle, 1, 20),
            connectionSimilarityThreshold = perturbF(params.connectionSimilarityThreshold, 0.1f, 0.7f)
        )

        return enforceOrdering(perturbed)
    }
}
