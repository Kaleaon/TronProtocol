package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SleepCycleTakensTrainer] — focuses on the pure data
 * classes and helper logic that don't require an Android Context.
 *
 * The trainer itself wraps [PixelTpuTrainingManager] which needs NNAPI,
 * so we test the result model, epoch selection, convergence logic, and
 * loss-improvement calculation independently.
 */
class SleepCycleTakensTrainerTest {

    // ====================================================================
    // SleepTrainingResult — construction & defaults
    // ====================================================================

    @Test
    fun testSkippedResultDefaults() {
        val result = SleepCycleTakensTrainer.SleepTrainingResult.skipped("no_hardware")
        assertFalse(result.trained)
        assertEquals("no_hardware", result.reason)
        assertEquals(0, result.epochs)
        assertEquals(0, result.sequences)
        assertEquals(0, result.vocabSize)
        assertEquals(0L, result.params)
        assertEquals(0L, result.duration)
        assertEquals(0f, result.lossImprovement, 0.001f)
        assertFalse(result.converged)
    }

    @Test
    fun testTrainedResultHoldsValues() {
        val result = SleepCycleTakensTrainer.SleepTrainingResult(
            trained = true,
            reason = "converged",
            epochs = 5,
            finalLoss = 2.15f,
            finalPerplexity = 8.58f,
            converged = true,
            sequences = 120,
            vocabSize = 72,
            params = 150_000L,
            duration = 4500L,
            lossImprovement = 0.18f
        )
        assertTrue(result.trained)
        assertEquals("converged", result.reason)
        assertEquals(5, result.epochs)
        assertEquals(2.15f, result.finalLoss, 0.001f)
        assertEquals(8.58f, result.finalPerplexity, 0.001f)
        assertTrue(result.converged)
        assertEquals(120, result.sequences)
        assertEquals(72, result.vocabSize)
        assertEquals(150_000L, result.params)
        assertEquals(4500L, result.duration)
        assertEquals(0.18f, result.lossImprovement, 0.001f)
    }

    // ====================================================================
    // SleepTrainingResult — toString
    // ====================================================================

    @Test
    fun testSkippedResultToString() {
        val result = SleepCycleTakensTrainer.SleepTrainingResult.skipped("insufficient_data")
        val str = result.toString()
        assertTrue(str.contains("skipped=insufficient_data"))
        assertFalse(str.contains("epochs="))
    }

    @Test
    fun testTrainedResultToString() {
        val result = SleepCycleTakensTrainer.SleepTrainingResult(
            trained = true,
            reason = "converged",
            epochs = 10,
            finalLoss = 1.85f,
            finalPerplexity = 6.36f,
            converged = true,
            sequences = 50,
            vocabSize = 60,
            params = 80_000L,
            duration = 2000L,
            lossImprovement = 0.25f
        )
        val str = result.toString()
        assertTrue(str.contains("reason=converged"))
        assertTrue(str.contains("epochs=10"))
        assertTrue(str.contains("loss="))
        assertTrue(str.contains("ppl="))
        assertTrue(str.contains("improvement="))
        assertTrue(str.contains("seqs=50"))
        assertTrue(str.contains("params=80000"))
        assertTrue(str.contains("duration=2000ms"))
    }

    // ====================================================================
    // SleepTrainingResult — data class equality & copy
    // ====================================================================

    @Test
    fun testResultEqualityForSkipped() {
        val a = SleepCycleTakensTrainer.SleepTrainingResult.skipped("no_data")
        val b = SleepCycleTakensTrainer.SleepTrainingResult.skipped("no_data")
        assertEquals(a, b)
    }

    @Test
    fun testResultCopy() {
        val original = SleepCycleTakensTrainer.SleepTrainingResult(
            trained = true,
            reason = "trained",
            epochs = 3,
            finalLoss = 3.0f,
            finalPerplexity = 20.0f,
            converged = false,
            sequences = 30,
            vocabSize = 40,
            params = 50_000L,
            duration = 1000L,
            lossImprovement = 0.05f
        )
        val modified = original.copy(converged = true, reason = "converged")
        assertTrue(modified.converged)
        assertEquals("converged", modified.reason)
        // Unchanged fields should remain the same
        assertEquals(original.epochs, modified.epochs)
        assertEquals(original.finalLoss, modified.finalLoss, 0.001f)
        assertEquals(original.sequences, modified.sequences)
    }

    // ====================================================================
    // Epoch budget selection logic (mirrored from SleepCycleTakensTrainer)
    // ====================================================================

    @Test
    fun testEpochBudgetSmallDataset() {
        // < 50 sequences → 10 epochs
        assertEquals(10, selectEpochBudget(10))
        assertEquals(10, selectEpochBudget(25))
        assertEquals(10, selectEpochBudget(49))
        assertEquals(10, selectEpochBudget(50))
    }

    @Test
    fun testEpochBudgetMediumDataset() {
        // 51..200 sequences → 5 epochs
        assertEquals(5, selectEpochBudget(51))
        assertEquals(5, selectEpochBudget(100))
        assertEquals(5, selectEpochBudget(200))
    }

    @Test
    fun testEpochBudgetLargeDataset() {
        // > 200 sequences → 3 epochs
        assertEquals(3, selectEpochBudget(201))
        assertEquals(3, selectEpochBudget(500))
        assertEquals(3, selectEpochBudget(10_000))
    }

    // ====================================================================
    // Convergence logic (mirrored from SleepCycleTakensTrainer)
    // ====================================================================

    @Test
    fun testDidConverge_LossDecreased() {
        assertTrue(didConverge(listOf(3.5f, 3.0f, 2.5f)))
    }

    @Test
    fun testDidConverge_LossIncreased() {
        assertFalse(didConverge(listOf(2.0f, 2.5f, 3.0f)))
    }

    @Test
    fun testDidConverge_SingleEpoch() {
        assertFalse(didConverge(listOf(2.5f)))
    }

    @Test
    fun testDidConverge_EmptyLosses() {
        assertFalse(didConverge(emptyList()))
    }

    @Test
    fun testDidConverge_NaNLoss() {
        assertFalse(didConverge(listOf(Float.NaN, 2.0f)))
        assertFalse(didConverge(listOf(2.0f, Float.NaN)))
    }

    @Test
    fun testDidConverge_InfiniteLoss() {
        assertFalse(didConverge(listOf(Float.POSITIVE_INFINITY, 2.0f)))
        assertFalse(didConverge(listOf(2.0f, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun testDidConverge_EqualLoss() {
        // Equal means no decrease, so not converged
        assertFalse(didConverge(listOf(2.5f, 2.5f)))
    }

    // ====================================================================
    // Loss improvement calculation (mirrored from SleepCycleTakensTrainer)
    // ====================================================================

    @Test
    fun testLossImprovement_Decreased() {
        val improvement = computeLossImprovement(listOf(4.0f, 3.0f))
        assertEquals(0.25f, improvement, 0.001f) // (4-3)/4 = 0.25
    }

    @Test
    fun testLossImprovement_Increased() {
        val improvement = computeLossImprovement(listOf(3.0f, 4.0f))
        // (3-4)/3 = -0.333 → negative improvement
        assertTrue(improvement < 0f)
    }

    @Test
    fun testLossImprovement_NoChange() {
        val improvement = computeLossImprovement(listOf(3.0f, 3.0f))
        assertEquals(0f, improvement, 0.001f)
    }

    @Test
    fun testLossImprovement_SingleEpoch() {
        assertEquals(0f, computeLossImprovement(listOf(3.0f)), 0.001f)
    }

    @Test
    fun testLossImprovement_EmptyLosses() {
        assertEquals(0f, computeLossImprovement(emptyList()), 0.001f)
    }

    @Test
    fun testLossImprovement_ZeroFirstLoss() {
        assertEquals(0f, computeLossImprovement(listOf(0f, 1.0f)), 0.001f)
    }

    @Test
    fun testLossImprovement_NaNValues() {
        assertEquals(0f, computeLossImprovement(listOf(Float.NaN, 2.0f)), 0.001f)
        assertEquals(0f, computeLossImprovement(listOf(2.0f, Float.NaN)), 0.001f)
    }

    // ====================================================================
    // Minimum sequences constant
    // ====================================================================

    @Test
    fun testMinSequencesIsReasonable() {
        // The minimum should be a small positive number — sanity check
        assertTrue(MIN_SEQUENCES > 0)
        assertTrue(MIN_SEQUENCES <= 50)
    }

    // ====================================================================
    // Helpers — mirror SleepCycleTakensTrainer's internal logic
    // ====================================================================

    /** Mirror of SleepCycleTakensTrainer.selectEpochBudget */
    private fun selectEpochBudget(sequenceCount: Int): Int = when {
        sequenceCount > 200 -> EPOCHS_LARGE
        sequenceCount > 50 -> EPOCHS_MEDIUM
        else -> EPOCHS_SMALL
    }

    /** Mirror of SleepCycleTakensTrainer.didConverge */
    private fun didConverge(epochLosses: List<Float>): Boolean {
        if (epochLosses.size < 2) return false
        val first = epochLosses.first()
        val last = epochLosses.last()
        return last.isFinite() && first.isFinite() && last < first
    }

    /** Mirror of SleepCycleTakensTrainer.computeLossImprovement */
    private fun computeLossImprovement(epochLosses: List<Float>): Float {
        if (epochLosses.size < 2) return 0f
        val first = epochLosses.first()
        val last = epochLosses.last()
        if (!first.isFinite() || !last.isFinite() || first <= 0f) return 0f
        return (first - last) / first
    }

    companion object {
        // Constants mirrored from SleepCycleTakensTrainer
        private const val MIN_SEQUENCES = 10
        private const val EPOCHS_SMALL = 10
        private const val EPOCHS_MEDIUM = 5
        private const val EPOCHS_LARGE = 3
    }
}
