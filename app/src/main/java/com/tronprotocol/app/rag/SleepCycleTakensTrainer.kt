package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import com.tronprotocol.app.aimodel.PixelTpuTrainingManager
import com.tronprotocol.app.aimodel.TrainingMetrics
import kotlin.math.exp

/**
 * Sleep-Cycle Takens Model Trainer
 *
 * Runs during consolidation sleep cycles to train (or retrain) a micro
 * Takens Embedding Transformer on the highest-quality knowledge in the
 * RAG store. This makes the on-device model progressively better at
 * representing the user's actual knowledge domain.
 *
 * Each sleep cycle:
 * 1. CHECK hardware capability (need NNAPI + 64MB free RAM)
 * 2. PREPARE training data from RAG store (MEMRL-weighted, so high-Q chunks dominate)
 * 3. TRAIN a micro model for a budget of 3-10 epochs (based on data volume)
 * 4. EVALUATE convergence (loss decreased? perplexity reasonable?)
 * 5. SAVE the model for inference during wake periods
 *
 * The micro model (~50K-200K params) is small enough to train fully on-device
 * in under a minute, making it practical for sleep-cycle retraining.
 *
 * @see PixelTpuTrainingManager
 */
class SleepCycleTakensTrainer(context: Context) {

    private val trainingManager = PixelTpuTrainingManager(context)

    /**
     * Run one training cycle during sleep/consolidation.
     *
     * @param ragStore The main RAG store — high-Q-value chunks are used as training data
     * @return Result describing what happened
     */
    fun trainDuringSleep(ragStore: RAGStore): SleepTrainingResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Sleep-cycle Takens training starting...")

        // Step 1: Check hardware capability
        val cap = trainingManager.getTpuCapability()
        if (!cap.canTrain) {
            Log.d(TAG, "Hardware cannot train: ${cap.reason}")
            return SleepTrainingResult.skipped("hardware_insufficient: ${cap.reason}")
        }

        // Step 2: Prepare training data from RAG store
        // prepareTrainingData uses MEMRL retrieval, which naturally prioritizes
        // high-Q-value chunks — proven valuable knowledge
        val data = trainingManager.prepareTrainingData(ragStore)
        if (data.sequences.isEmpty()) {
            Log.d(TAG, "No training data available from RAG store")
            return SleepTrainingResult.skipped("no_training_data")
        }

        if (data.sequences.size < MIN_SEQUENCES) {
            Log.d(TAG, "Insufficient sequences (${data.sequences.size}/$MIN_SEQUENCES)")
            return SleepTrainingResult.skipped("insufficient_data")
        }

        // Step 3: Select epoch budget based on data volume
        val epochs = selectEpochBudget(data.sequences.size)
        Log.d(TAG, "Training: ${data.sequences.size} sequences, vocab=${data.vocabulary.size}, epochs=$epochs")

        // Step 4: Train
        val metrics: TrainingMetrics
        try {
            metrics = trainingManager.train(data, epochs = epochs)
        } catch (e: Exception) {
            Log.e(TAG, "Training failed", e)
            return SleepTrainingResult(
                trained = false,
                reason = "training_error: ${e.message}",
                duration = System.currentTimeMillis() - startTime
            )
        }

        // Step 5: Evaluate convergence
        val converged = didConverge(metrics)

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Sleep-cycle training complete: loss=${"%.4f".format(metrics.finalLoss)}, " +
                "ppl=${"%.2f".format(metrics.finalPerplexity)}, converged=$converged, " +
                "duration=${duration}ms")

        return SleepTrainingResult(
            trained = true,
            reason = if (converged) "converged" else "trained",
            epochs = metrics.epochsTrained,
            finalLoss = metrics.finalLoss,
            finalPerplexity = metrics.finalPerplexity,
            converged = converged,
            sequences = data.sequences.size,
            vocabSize = data.vocabulary.size,
            params = metrics.totalParams,
            duration = duration,
            lossImprovement = computeLossImprovement(metrics)
        )
    }

    /**
     * Check if the last training run converged (loss decreased over epochs).
     */
    private fun didConverge(metrics: TrainingMetrics): Boolean {
        if (metrics.epochLosses.size < 2) return false
        val firstLoss = metrics.epochLosses.first()
        val lastLoss = metrics.epochLosses.last()
        // Converged if loss decreased and is finite
        return lastLoss.isFinite() && firstLoss.isFinite() && lastLoss < firstLoss
    }

    /**
     * Compute the relative loss improvement from first to last epoch.
     * Returns a value like 0.15 meaning "15% reduction in loss".
     */
    private fun computeLossImprovement(metrics: TrainingMetrics): Float {
        if (metrics.epochLosses.size < 2) return 0f
        val first = metrics.epochLosses.first()
        val last = metrics.epochLosses.last()
        if (!first.isFinite() || !last.isFinite() || first <= 0f) return 0f
        return (first - last) / first
    }

    /**
     * Select training epoch budget based on data volume.
     * More data requires fewer epochs for convergence; capped for sleep budget.
     */
    private fun selectEpochBudget(sequenceCount: Int): Int = when {
        sequenceCount > 200 -> EPOCHS_LARGE_DATASET
        sequenceCount > 50 -> EPOCHS_MEDIUM_DATASET
        else -> EPOCHS_SMALL_DATASET
    }

    fun shutdown() {
        trainingManager.shutdown()
    }

    /**
     * Result of a sleep-cycle training run.
     */
    data class SleepTrainingResult(
        /** Whether training actually ran (false = skipped). */
        val trained: Boolean,
        /** Why training was skipped, or "converged"/"trained" on success. */
        val reason: String = "",
        /** Epochs completed. */
        val epochs: Int = 0,
        /** Final cross-entropy loss. */
        val finalLoss: Float = Float.NaN,
        /** Final perplexity (exp of loss). */
        val finalPerplexity: Float = Float.NaN,
        /** Whether loss decreased over the training run. */
        val converged: Boolean = false,
        /** Number of training sequences used. */
        val sequences: Int = 0,
        /** Vocabulary size (character-level). */
        val vocabSize: Int = 0,
        /** Total model parameters. */
        val params: Long = 0,
        /** Total wall-clock duration in milliseconds. */
        val duration: Long = 0,
        /** Relative loss improvement (0.0 to 1.0). */
        val lossImprovement: Float = 0f
    ) {
        override fun toString(): String =
            if (trained) {
                "SleepTraining{reason=$reason, epochs=$epochs, " +
                        "loss=${"%.4f".format(finalLoss)}, ppl=${"%.2f".format(finalPerplexity)}, " +
                        "improvement=${"%.1f%%".format(lossImprovement * 100)}, " +
                        "seqs=$sequences, params=$params, duration=${duration}ms}"
            } else {
                "SleepTraining{skipped=$reason}"
            }

        companion object {
            fun skipped(reason: String) = SleepTrainingResult(trained = false, reason = reason)
        }
    }

    companion object {
        private const val TAG = "SleepCycleTakens"

        /** Minimum sequences required to justify a training run. */
        private const val MIN_SEQUENCES = 10

        /** Epoch budgets by dataset size. */
        private const val EPOCHS_SMALL_DATASET = 10
        private const val EPOCHS_MEDIUM_DATASET = 5
        private const val EPOCHS_LARGE_DATASET = 3
    }
}
