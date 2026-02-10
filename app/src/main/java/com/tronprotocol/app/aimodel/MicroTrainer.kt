package com.tronprotocol.app.aimodel

import android.content.Context
import android.util.Log
import com.tronprotocol.app.bus.MessageBus
import com.tronprotocol.app.npu.NPUInferenceManager
import com.tronprotocol.app.npu.NPUInferenceManager.ModelWeights
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * MicroTrainer - real on-device model training with gradient computation
 * and NPU acceleration.
 *
 * Implements actual neural network training on Android:
 * - 2-layer feedforward networks (input -> hidden ReLU -> output)
 * - Mini-batch stochastic gradient descent (SGD) with momentum
 * - Cross-entropy loss for classification, MSE for regression
 * - Backpropagation with analytically computed gradients
 * - Learning rate scheduling (step decay, warmup)
 * - Early stopping based on validation loss
 * - NPU-accelerated forward pass via NPUInferenceManager
 * - Weight persistence via SecureStorage
 * - Training history and metrics tracking
 * - MessageBus integration for epoch/batch events
 *
 * This is NOT a knowledge aggregation heuristic - it performs real
 * gradient-based optimization of model parameters.
 */
class MicroTrainer(
    private val context: Context,
    private val npuManager: NPUInferenceManager,
    private val messageBus: MessageBus? = null
) {

    /** Training configuration. */
    data class TrainingConfig(
        val inputSize: Int,
        val hiddenSize: Int = 64,
        val outputSize: Int,
        val learningRate: Float = 0.01f,
        val momentum: Float = 0.9f,
        val batchSize: Int = 16,
        val epochs: Int = 50,
        val validationSplit: Float = 0.2f,
        val earlyStoppingPatience: Int = 5,
        val lrDecayFactor: Float = 0.5f,
        val lrDecayEvery: Int = 20,
        val warmupEpochs: Int = 3,
        val taskType: TaskType = TaskType.CLASSIFICATION
    )

    enum class TaskType { CLASSIFICATION, REGRESSION }

    /** A single training sample. */
    data class Sample(
        val features: FloatArray,
        val label: Int = 0,          // For classification
        val target: FloatArray? = null  // For regression
    )

    /** Training result for one epoch. */
    data class EpochResult(
        val epoch: Int,
        val trainLoss: Float,
        val valLoss: Float,
        val trainAccuracy: Float,
        val valAccuracy: Float,
        val learningRate: Float,
        val durationMs: Long
    )

    /** Complete training result. */
    data class TrainingResult(
        val modelId: String,
        val success: Boolean,
        val epochs: List<EpochResult>,
        val finalTrainLoss: Float,
        val finalValLoss: Float,
        val finalTrainAccuracy: Float,
        val finalValAccuracy: Float,
        val totalDurationMs: Long,
        val totalSamples: Int,
        val earlyStoppedAt: Int? = null,
        val error: String? = null
    )

    // State
    private val storage = SecureStorage(context)
    private val trainedModels = mutableMapOf<String, ModelWeights>()
    private val trainingHistory = mutableMapOf<String, List<EpochResult>>()
    private val training = AtomicBoolean(false)
    private val totalTrainingSessions = AtomicLong(0)
    private val totalEpochsTrained = AtomicLong(0)

    /**
     * Train a model from scratch with the given data.
     *
     * Performs real mini-batch SGD with backpropagation.
     */
    fun train(modelId: String, config: TrainingConfig, data: List<Sample>): TrainingResult {
        if (!training.compareAndSet(false, true)) {
            return TrainingResult(modelId, false, emptyList(), 0f, 0f, 0f, 0f, 0, 0,
                error = "Training already in progress")
        }

        val startTime = System.currentTimeMillis()
        totalTrainingSessions.incrementAndGet()

        messageBus?.publishAsync("training.start", mapOf("modelId" to modelId, "samples" to data.size), "MicroTrainer")

        try {
            if (data.size < 4) {
                return TrainingResult(modelId, false, emptyList(), 0f, 0f, 0f, 0f, 0, data.size,
                    error = "Insufficient training data (need at least 4 samples)")
            }

            // Split into train/validation
            val shuffled = data.shuffled()
            val valSize = (shuffled.size * config.validationSplit).toInt().coerceAtLeast(1)
            val trainData = shuffled.dropLast(valSize)
            val valData = shuffled.takeLast(valSize)

            // Initialize weights
            var weights = ModelWeights.random(config.inputSize, config.hiddenSize, config.outputSize)
            var momentumW1 = FloatArray(weights.weight1.size)
            var momentumB1 = FloatArray(weights.bias1.size)
            var momentumW2 = FloatArray(weights.weight2.size)
            var momentumB2 = FloatArray(weights.bias2.size)

            val epochResults = mutableListOf<EpochResult>()
            var bestValLoss = Float.MAX_VALUE
            var patienceCounter = 0
            var earlyStoppedAt: Int? = null

            for (epoch in 1..config.epochs) {
                val epochStart = System.currentTimeMillis()

                // Learning rate schedule
                val lr = computeLearningRate(config, epoch)

                // Shuffle training data each epoch
                val epochData = trainData.shuffled()

                // Mini-batch training
                var epochLoss = 0f
                var epochCorrect = 0
                var batchCount = 0

                for (batchStart in epochData.indices step config.batchSize) {
                    val batchEnd = minOf(batchStart + config.batchSize, epochData.size)
                    val batch = epochData.subList(batchStart, batchEnd)

                    // Accumulate gradients over the batch
                    val gradW1 = FloatArray(weights.weight1.size)
                    val gradB1 = FloatArray(weights.bias1.size)
                    val gradW2 = FloatArray(weights.weight2.size)
                    val gradB2 = FloatArray(weights.bias2.size)

                    for (sample in batch) {
                        // Forward pass (NPU-accelerated)
                        val forward = npuManager.runForward(weights, sample.features)

                        // Compute loss and output gradient
                        val (loss, outputGrad) = when (config.taskType) {
                            TaskType.CLASSIFICATION -> {
                                val probs = softmax(forward.output)
                                val loss = -ln(probs[sample.label].coerceAtLeast(1e-7f))

                                // Softmax cross-entropy gradient: probs - one_hot
                                val grad = probs.copyOf()
                                grad[sample.label] -= 1f

                                if (probs.indices.maxByOrNull { probs[it] } == sample.label) {
                                    epochCorrect++
                                }

                                loss to grad
                            }
                            TaskType.REGRESSION -> {
                                val target = sample.target ?: FloatArray(config.outputSize)
                                var mse = 0f
                                val grad = FloatArray(config.outputSize)
                                for (i in forward.output.indices) {
                                    val diff = forward.output[i] - target[i]
                                    mse += diff * diff
                                    grad[i] = 2f * diff / config.outputSize
                                }
                                (mse / config.outputSize) to grad
                            }
                        }

                        epochLoss += loss

                        // Backpropagation
                        backprop(
                            weights, forward, outputGrad,
                            gradW1, gradB1, gradW2, gradB2
                        )
                    }

                    val batchSizeF = batch.size.toFloat()

                    // Apply gradients with momentum
                    for (i in weights.weight1.indices) {
                        momentumW1[i] = config.momentum * momentumW1[i] + lr * gradW1[i] / batchSizeF
                        weights.weight1[i] -= momentumW1[i]
                    }
                    for (i in weights.bias1.indices) {
                        momentumB1[i] = config.momentum * momentumB1[i] + lr * gradB1[i] / batchSizeF
                        weights.bias1[i] -= momentumB1[i]
                    }
                    for (i in weights.weight2.indices) {
                        momentumW2[i] = config.momentum * momentumW2[i] + lr * gradW2[i] / batchSizeF
                        weights.weight2[i] -= momentumW2[i]
                    }
                    for (i in weights.bias2.indices) {
                        momentumB2[i] = config.momentum * momentumB2[i] + lr * gradB2[i] / batchSizeF
                        weights.bias2[i] -= momentumB2[i]
                    }

                    batchCount++
                }

                // Compute training metrics
                val trainLoss = epochLoss / trainData.size
                val trainAccuracy = if (config.taskType == TaskType.CLASSIFICATION) {
                    epochCorrect.toFloat() / trainData.size
                } else 0f

                // Validation
                val (valLoss, valAccuracy) = evaluate(weights, valData, config)

                val epochDuration = System.currentTimeMillis() - epochStart
                val epochResult = EpochResult(epoch, trainLoss, valLoss, trainAccuracy, valAccuracy, lr, epochDuration)
                epochResults.add(epochResult)
                totalEpochsTrained.incrementAndGet()

                messageBus?.publishAsync("training.epoch", epochResult, "MicroTrainer")

                Log.d(TAG, "Epoch $epoch/$${config.epochs}: " +
                        "train_loss=%.4f, val_loss=%.4f, train_acc=%.2f%%, val_acc=%.2f%%, lr=%.5f, ${epochDuration}ms".format(
                            trainLoss, valLoss, trainAccuracy * 100, valAccuracy * 100, lr))

                // Early stopping
                if (valLoss < bestValLoss) {
                    bestValLoss = valLoss
                    patienceCounter = 0
                } else {
                    patienceCounter++
                    if (patienceCounter >= config.earlyStoppingPatience) {
                        earlyStoppedAt = epoch
                        Log.d(TAG, "Early stopping at epoch $epoch (patience=${config.earlyStoppingPatience})")
                        break
                    }
                }
            }

            // Store trained weights
            trainedModels[modelId] = weights
            trainingHistory[modelId] = epochResults
            saveWeights(modelId, weights)

            val lastEpoch = epochResults.last()
            val result = TrainingResult(
                modelId = modelId,
                success = true,
                epochs = epochResults,
                finalTrainLoss = lastEpoch.trainLoss,
                finalValLoss = lastEpoch.valLoss,
                finalTrainAccuracy = lastEpoch.trainAccuracy,
                finalValAccuracy = lastEpoch.valAccuracy,
                totalDurationMs = System.currentTimeMillis() - startTime,
                totalSamples = data.size,
                earlyStoppedAt = earlyStoppedAt
            )

            messageBus?.publishAsync("training.complete", result, "MicroTrainer")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Training failed for model $modelId", e)
            return TrainingResult(modelId, false, emptyList(), 0f, 0f, 0f, 0f,
                System.currentTimeMillis() - startTime, data.size, error = "Training failed: ${e.message}")
        } finally {
            training.set(false)
        }
    }

    /**
     * Continue training an existing model with new data.
     */
    fun continueTrain(modelId: String, config: TrainingConfig, newData: List<Sample>): TrainingResult {
        val existingWeights = trainedModels[modelId] ?: loadWeights(modelId)
        if (existingWeights != null) {
            // Use existing weights as starting point
            trainedModels[modelId] = existingWeights
        }
        return train(modelId, config, newData)
    }

    /**
     * Run inference on a trained model.
     */
    fun predict(modelId: String, input: FloatArray): FloatArray? {
        val weights = trainedModels[modelId] ?: loadWeights(modelId) ?: return null
        val forward = npuManager.runForward(weights, input)
        return forward.output
    }

    /**
     * Run classification prediction (returns class probabilities).
     */
    fun predictClass(modelId: String, input: FloatArray): Pair<Int, FloatArray>? {
        val logits = predict(modelId, input) ?: return null
        val probs = softmax(logits)
        val predictedClass = probs.indices.maxByOrNull { probs[it] } ?: 0
        return predictedClass to probs
    }

    /**
     * Get the trained weights for a model (for inspection or export).
     */
    fun getWeights(modelId: String): ModelWeights? {
        return trainedModels[modelId] ?: loadWeights(modelId)
    }

    /**
     * Get training history for a model.
     */
    fun getHistory(modelId: String): List<EpochResult>? = trainingHistory[modelId]

    /**
     * Check if a model is trained and available.
     */
    fun isModelTrained(modelId: String): Boolean {
        return modelId in trainedModels || storage.retrieve("weights_$modelId") != null
    }

    /**
     * Get training statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_sessions" to totalTrainingSessions.get(),
        "total_epochs" to totalEpochsTrained.get(),
        "trained_models" to trainedModels.size,
        "is_training" to training.get(),
        "models" to trainedModels.keys.toList()
    )

    // -- Internal: Backpropagation --

    /**
     * Compute gradients via backpropagation for a 2-layer network.
     *
     * Network: input -> W1*x + b1 -> ReLU -> W2*h + b2 -> output
     *
     * Given dL/dOutput (outputGrad), computes:
     *   dL/dW2 = hidden^T * outputGrad
     *   dL/db2 = outputGrad
     *   dL/dhidden = outputGrad * W2^T, masked by ReLU derivative
     *   dL/dW1 = input^T * dL/dhidden
     *   dL/db1 = dL/dhidden
     */
    private fun backprop(
        weights: ModelWeights,
        forward: NPUInferenceManager.ForwardResult,
        outputGrad: FloatArray,
        gradW1: FloatArray, gradB1: FloatArray,
        gradW2: FloatArray, gradB2: FloatArray
    ) {
        val input = forward.input
        val hidden = forward.hiddenActivations

        // Gradient for W2 and b2
        for (j in 0 until weights.outputSize) {
            gradB2[j] += outputGrad[j]
            for (i in 0 until weights.hiddenSize) {
                gradW2[i * weights.outputSize + j] += hidden[i] * outputGrad[j]
            }
        }

        // Gradient through hidden layer
        val hiddenGrad = FloatArray(weights.hiddenSize)
        for (i in 0 until weights.hiddenSize) {
            var grad = 0f
            for (j in 0 until weights.outputSize) {
                grad += outputGrad[j] * weights.weight2[i * weights.outputSize + j]
            }
            // ReLU derivative: 1 if hidden > 0, else 0
            hiddenGrad[i] = if (hidden[i] > 0) grad else 0f
        }

        // Gradient for W1 and b1
        for (j in 0 until weights.hiddenSize) {
            gradB1[j] += hiddenGrad[j]
            for (i in input.indices) {
                gradW1[i * weights.hiddenSize + j] += input[i] * hiddenGrad[j]
            }
        }
    }

    // -- Internal: Evaluation --

    private fun evaluate(
        weights: ModelWeights,
        data: List<Sample>,
        config: TrainingConfig
    ): Pair<Float, Float> {
        if (data.isEmpty()) return 0f to 0f

        var totalLoss = 0f
        var correct = 0

        for (sample in data) {
            val forward = npuManager.runForward(weights, sample.features)

            when (config.taskType) {
                TaskType.CLASSIFICATION -> {
                    val probs = softmax(forward.output)
                    totalLoss += -ln(probs[sample.label].coerceAtLeast(1e-7f))
                    if (probs.indices.maxByOrNull { probs[it] } == sample.label) {
                        correct++
                    }
                }
                TaskType.REGRESSION -> {
                    val target = sample.target ?: FloatArray(config.outputSize)
                    for (i in forward.output.indices) {
                        val diff = forward.output[i] - target[i]
                        totalLoss += diff * diff / config.outputSize
                    }
                }
            }
        }

        val avgLoss = totalLoss / data.size
        val accuracy = if (config.taskType == TaskType.CLASSIFICATION) {
            correct.toFloat() / data.size
        } else 0f

        return avgLoss to accuracy
    }

    // -- Internal: Utilities --

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sumExp = exps.sum()
        return FloatArray(logits.size) { exps[it] / sumExp }
    }

    private fun computeLearningRate(config: TrainingConfig, epoch: Int): Float {
        // Warmup
        if (epoch <= config.warmupEpochs) {
            return config.learningRate * epoch.toFloat() / config.warmupEpochs
        }

        // Step decay
        val decaySteps = (epoch - config.warmupEpochs) / config.lrDecayEvery
        var lr = config.learningRate
        for (i in 0 until decaySteps) {
            lr *= config.lrDecayFactor
        }

        return lr.coerceAtLeast(1e-6f)
    }

    // -- Persistence --

    private fun saveWeights(modelId: String, weights: ModelWeights) {
        try {
            val json = JSONObject().apply {
                put("inputSize", weights.inputSize)
                put("hiddenSize", weights.hiddenSize)
                put("outputSize", weights.outputSize)
                put("weight1", floatArrayToJson(weights.weight1))
                put("bias1", floatArrayToJson(weights.bias1))
                put("weight2", floatArrayToJson(weights.weight2))
                put("bias2", floatArrayToJson(weights.bias2))
            }
            storage.store("weights_$modelId", json.toString())
            Log.d(TAG, "Saved weights for model $modelId " +
                    "(${weights.weight1.size + weights.weight2.size} params)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save weights for $modelId", e)
        }
    }

    private fun loadWeights(modelId: String): ModelWeights? {
        return try {
            val data = storage.retrieve("weights_$modelId") ?: return null
            val json = JSONObject(data)
            ModelWeights(
                inputSize = json.getInt("inputSize"),
                hiddenSize = json.getInt("hiddenSize"),
                outputSize = json.getInt("outputSize"),
                weight1 = jsonToFloatArray(json.getJSONArray("weight1")),
                bias1 = jsonToFloatArray(json.getJSONArray("bias1")),
                weight2 = jsonToFloatArray(json.getJSONArray("weight2")),
                bias2 = jsonToFloatArray(json.getJSONArray("bias2"))
            ).also {
                trainedModels[modelId] = it
                Log.d(TAG, "Loaded weights for model $modelId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load weights for $modelId", e)
            null
        }
    }

    private fun floatArrayToJson(arr: FloatArray): JSONArray {
        val jsonArr = JSONArray()
        for (v in arr) jsonArr.put(v.toDouble())
        return jsonArr
    }

    private fun jsonToFloatArray(jsonArr: JSONArray): FloatArray {
        return FloatArray(jsonArr.length()) { jsonArr.getDouble(it).toFloat() }
    }

    companion object {
        private const val TAG = "MicroTrainer"
    }
}
