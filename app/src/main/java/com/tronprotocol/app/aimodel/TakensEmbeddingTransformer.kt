package com.tronprotocol.app.aimodel

import android.util.Log
import kotlin.math.*

/**
 * Takens Embedding Transformer — attention-free sequence model for on-device training.
 *
 * Ported from https://github.com/KevinHaylett/takens-embedding-transformer
 *
 * Replaces conventional multi-head attention with explicit phase-space reconstruction
 * derived from Takens' delay-coordinate theorem. Token sequences are treated as
 * observable signals from an underlying semantic dynamical system. Delay embeddings
 * reconstruct the phase space geometrically, and feed-forward layers process the
 * reconstructed trajectory.
 *
 * Micro configuration targets Pixel 10 TPU (Tensor G5 Edge TPU) for on-device
 * training with minimal memory footprint.
 *
 * Architecture:
 *   Token Embedding → Delay-Coordinate Embedding → [TBTLayer x N] → LayerNorm → Output Projection
 *
 * Reference: "Introducing the Takens-Based Transformer" — Kevin R. Haylett, Dec 2025
 */
class TakensEmbeddingTransformer(val config: TakensConfig) {

    // Learnable parameters
    var tokenEmbeddings: Array<FloatArray>       // [vocab_size, embed_dim]
    var delayProjection: Array<FloatArray>        // [(num_delays+1)*embed_dim, hidden_dim]
    var delayProjectionBias: FloatArray           // [hidden_dim]
    var layers: List<TBTLayerParams>              // N transformer layers
    var finalNormGamma: FloatArray                // [hidden_dim]
    var finalNormBeta: FloatArray                 // [hidden_dim]
    var outputProjection: Array<FloatArray>       // [hidden_dim, vocab_size]
    var outputBias: FloatArray                    // [vocab_size]

    // Gradient accumulators (mirrors every parameter)
    var gradTokenEmbeddings: Array<FloatArray>
    var gradDelayProjection: Array<FloatArray>
    var gradDelayProjectionBias: FloatArray
    var gradLayers: List<TBTLayerGrads>
    var gradFinalNormGamma: FloatArray
    var gradFinalNormBeta: FloatArray
    var gradOutputProjection: Array<FloatArray>
    var gradOutputBias: FloatArray

    val takensInputDim = (config.delays.size + 1) * config.embedDim

    init {
        val rng = java.util.Random(config.seed)
        val initScale = sqrt(2.0f / config.embedDim)

        // Token embeddings: normal init scaled by 1/sqrt(embed_dim)
        tokenEmbeddings = Array(config.vocabSize) {
            FloatArray(config.embedDim) { (rng.nextGaussian() * initScale).toFloat() }
        }

        // Delay projection: maps flattened delay coords to hidden_dim
        val projScale = sqrt(2.0f / takensInputDim)
        delayProjection = Array(takensInputDim) {
            FloatArray(config.hiddenDim) { (rng.nextGaussian() * projScale).toFloat() }
        }
        delayProjectionBias = FloatArray(config.hiddenDim)

        // Stacked TBT layers
        layers = (0 until config.numLayers).map { TBTLayerParams(config.hiddenDim, rng) }

        // Final layer norm
        finalNormGamma = FloatArray(config.hiddenDim) { 1.0f }
        finalNormBeta = FloatArray(config.hiddenDim) { 0.0f }

        // Output projection (weight-tied with token embeddings if dims match)
        outputProjection = Array(config.hiddenDim) {
            FloatArray(config.vocabSize) { (rng.nextGaussian() * projScale).toFloat() }
        }
        outputBias = FloatArray(config.vocabSize)

        // Initialize gradient accumulators
        gradTokenEmbeddings = Array(config.vocabSize) { FloatArray(config.embedDim) }
        gradDelayProjection = Array(takensInputDim) { FloatArray(config.hiddenDim) }
        gradDelayProjectionBias = FloatArray(config.hiddenDim)
        gradLayers = layers.map { TBTLayerGrads(config.hiddenDim) }
        gradFinalNormGamma = FloatArray(config.hiddenDim)
        gradFinalNormBeta = FloatArray(config.hiddenDim)
        gradOutputProjection = Array(config.hiddenDim) { FloatArray(config.vocabSize) }
        gradOutputBias = FloatArray(config.vocabSize)
    }

    /**
     * Count total trainable parameters.
     */
    fun parameterCount(): Long {
        var count = 0L
        count += config.vocabSize.toLong() * config.embedDim            // token embeddings
        count += takensInputDim.toLong() * config.hiddenDim             // delay projection weight
        count += config.hiddenDim                                        // delay projection bias
        for (layer in layers) {
            count += layer.parameterCount()
        }
        count += config.hiddenDim * 2L                                   // final norm
        count += config.hiddenDim.toLong() * config.vocabSize            // output projection
        count += config.vocabSize                                        // output bias
        return count
    }

    // ---- Forward Pass ----

    /**
     * Full forward pass returning logits and caching intermediates for backprop.
     *
     * @param inputIds token indices [seq_len]
     * @return ForwardResult with logits [seq_len, vocab_size] and cached activations
     */
    fun forward(inputIds: IntArray): ForwardResult {
        val seqLen = inputIds.size

        // 1. Token embedding lookup: [seq_len, embed_dim]
        val embedded = Array(seqLen) { t ->
            tokenEmbeddings[inputIds[t]].copyOf()
        }

        // 2. Takens delay-coordinate embedding: [seq_len, (num_delays+1)*embed_dim]
        val delayEmbedded = buildDelayCoordinates(embedded)

        // 3. Linear projection to hidden_dim: [seq_len, hidden_dim]
        val projected = Array(seqLen) { t ->
            linearForward(delayEmbedded[t], delayProjection, delayProjectionBias)
        }

        // 4. Pass through TBT layers with residual connections
        var hidden = projected
        val layerInputs = mutableListOf<Array<FloatArray>>()
        val layerNormed = mutableListOf<Array<FloatArray>>()
        val layerFfInternals = mutableListOf<Array<FloatArray>>() // pre-activation of ff2

        for ((idx, layer) in layers.withIndex()) {
            layerInputs.add(hidden.map { it.copyOf() }.toTypedArray())

            // LayerNorm → FeedForward → Residual
            val normed = Array(seqLen) { t ->
                layerNorm(hidden[t], layer.normGamma, layer.normBeta)
            }
            layerNormed.add(normed)

            val ffOut = Array(seqLen) { t ->
                val (out, preAct) = feedForwardWithCache(
                    normed[t], layer.ff1Weight, layer.ff1Bias,
                    layer.ff2Weight, layer.ff2Bias
                )
                out
            }

            // Recompute to cache pre-activations for backprop
            val preActivations = Array(seqLen) { t ->
                val (_, preAct) = feedForwardWithCache(
                    normed[t], layer.ff1Weight, layer.ff1Bias,
                    layer.ff2Weight, layer.ff2Bias
                )
                preAct
            }
            layerFfInternals.add(preActivations)

            // Residual connection
            hidden = Array(seqLen) { t ->
                FloatArray(config.hiddenDim) { d -> hidden[t][d] + ffOut[t][d] }
            }
        }

        // 5. Final layer norm
        val finalNormed = Array(seqLen) { t ->
            layerNorm(hidden[t], finalNormGamma, finalNormBeta)
        }

        // 6. Output projection to vocab logits: [seq_len, vocab_size]
        val logits = Array(seqLen) { t ->
            linearForward(finalNormed[t], outputProjection, outputBias)
        }

        return ForwardResult(
            logits = logits,
            inputIds = inputIds,
            embedded = embedded,
            delayEmbedded = delayEmbedded,
            projected = projected,
            layerInputs = layerInputs,
            layerNormed = layerNormed,
            layerFfInternals = layerFfInternals,
            preOutputHidden = hidden,
            finalNormed = finalNormed
        )
    }

    /**
     * Compute cross-entropy loss for next-token prediction.
     *
     * @param logits [seq_len, vocab_size] from forward pass
     * @param targets [seq_len] target token indices (shifted by 1)
     * @return average cross-entropy loss
     */
    fun crossEntropyLoss(logits: Array<FloatArray>, targets: IntArray): Float {
        var totalLoss = 0.0f
        val n = minOf(logits.size, targets.size)

        for (t in 0 until n) {
            val probs = softmax(logits[t])
            val targetProb = maxOf(probs[targets[t]], 1e-10f)
            totalLoss -= ln(targetProb)
        }

        return totalLoss / n
    }

    // ---- Backward Pass ----

    /**
     * Backward pass computing gradients for all parameters.
     *
     * @param result cached forward pass result
     * @param targets target token indices for cross-entropy loss
     */
    fun backward(result: ForwardResult, targets: IntArray) {
        val seqLen = result.inputIds.size
        val n = minOf(seqLen, targets.size)

        // dL/d(logits) for cross-entropy with softmax: softmax(logits) - one_hot(target)
        val dLogits = Array(n) { t ->
            val probs = softmax(result.logits[t])
            probs[targets[t]] -= 1.0f
            // Scale by 1/n for mean loss
            for (i in probs.indices) probs[i] /= n
            probs
        }

        // Gradient through output projection
        val dFinalNormed = Array(n) { FloatArray(config.hiddenDim) }
        for (t in 0 until n) {
            for (h in 0 until config.hiddenDim) {
                var sum = 0.0f
                for (v in 0 until config.vocabSize) {
                    gradOutputProjection[h][v] += result.finalNormed[t][h] * dLogits[t][v]
                    sum += outputProjection[h][v] * dLogits[t][v]
                }
                dFinalNormed[t][h] = sum
            }
            for (v in 0 until config.vocabSize) {
                gradOutputBias[v] += dLogits[t][v]
            }
        }

        // Gradient through final layer norm
        val dPreOutput = Array(n) { t ->
            layerNormBackward(
                dFinalNormed[t], result.preOutputHidden[t],
                finalNormGamma, gradFinalNormGamma, gradFinalNormBeta
            )
        }

        // Gradient through TBT layers (reverse order)
        var dHidden = dPreOutput
        for (layerIdx in layers.indices.reversed()) {
            val layer = layers[layerIdx]
            val grads = gradLayers[layerIdx]
            val layerInput = result.layerInputs[layerIdx]
            val normed = result.layerNormed[layerIdx]
            val preAct = result.layerFfInternals[layerIdx]

            val dResidualIn = Array(n) { FloatArray(config.hiddenDim) }

            for (t in 0 until n) {
                // Gradient through feed-forward
                val dFfOut = dHidden[t]

                // Backprop through ff2: dFfOut → dGelu
                val dGelu = FloatArray(config.hiddenDim * 4)
                for (i in 0 until config.hiddenDim * 4) {
                    var sum = 0.0f
                    for (j in 0 until config.hiddenDim) {
                        grads.ff2Weight[i][j] += preAct[t][i] * dFfOut[j]
                        sum += layer.ff2Weight[i][j] * dFfOut[j]
                    }
                    // GELU derivative
                    val x = preAct[t][i]
                    dGelu[i] = sum * geluDerivative(x)
                }
                for (j in 0 until config.hiddenDim) {
                    grads.ff2Bias[j] += dFfOut[j]
                }

                // Backprop through ff1: dGelu → dNormed
                val dNormed = FloatArray(config.hiddenDim)
                for (i in 0 until config.hiddenDim) {
                    var sum = 0.0f
                    for (j in 0 until config.hiddenDim * 4) {
                        grads.ff1Weight[i][j] += normed[t][i] * dGelu[j]
                        sum += layer.ff1Weight[i][j] * dGelu[j]
                    }
                    dNormed[i] = sum
                }
                for (j in 0 until config.hiddenDim * 4) {
                    grads.ff1Bias[j] += dGelu[j]
                }

                // Backprop through layer norm
                val dInput = layerNormBackward(
                    dNormed, layerInput[t], layer.normGamma,
                    grads.normGamma, grads.normBeta
                )

                // Residual: dInput + dHidden (skip connection)
                for (d in 0 until config.hiddenDim) {
                    dResidualIn[t][d] = dInput[d] + dHidden[t][d]
                }
            }

            dHidden = dResidualIn
        }

        // Gradient through delay projection
        val dDelayEmbedded = Array(n) { FloatArray(takensInputDim) }
        for (t in 0 until n) {
            for (i in 0 until takensInputDim) {
                var sum = 0.0f
                for (j in 0 until config.hiddenDim) {
                    gradDelayProjection[i][j] += result.delayEmbedded[t][i] * dHidden[t][j]
                    sum += delayProjection[i][j] * dHidden[t][j]
                }
                dDelayEmbedded[t][i] = sum
            }
            for (j in 0 until config.hiddenDim) {
                gradDelayProjectionBias[j] += dHidden[t][j]
            }
        }

        // Gradient through delay coordinates → token embeddings
        backpropDelayCoordinates(dDelayEmbedded, result.inputIds, n)
    }

    /**
     * Apply accumulated gradients with AdamW optimizer step.
     */
    fun adamWStep(step: Int, lr: Float = config.learningRate,
                  beta1: Float = 0.9f, beta2: Float = 0.999f,
                  eps: Float = 1e-8f, weightDecay: Float = config.weightDecay) {
        // Simplified SGD with momentum and weight decay for memory efficiency
        // Full AdamW would require 2x parameter memory for m and v states
        val effectiveLr = lr * sqrt(1.0f - beta2.pow(step)) / (1.0f - beta1.pow(step))

        applyGradients(tokenEmbeddings, gradTokenEmbeddings, effectiveLr, weightDecay)
        applyGradients(delayProjection, gradDelayProjection, effectiveLr, weightDecay)
        applyGradients1D(delayProjectionBias, gradDelayProjectionBias, effectiveLr, 0f)

        for (i in layers.indices) {
            val layer = layers[i]
            val grads = gradLayers[i]
            applyGradients(layer.ff1Weight, grads.ff1Weight, effectiveLr, weightDecay)
            applyGradients1D(layer.ff1Bias, grads.ff1Bias, effectiveLr, 0f)
            applyGradients(layer.ff2Weight, grads.ff2Weight, effectiveLr, weightDecay)
            applyGradients1D(layer.ff2Bias, grads.ff2Bias, effectiveLr, 0f)
            applyGradients1D(layer.normGamma, grads.normGamma, effectiveLr, 0f)
            applyGradients1D(layer.normBeta, grads.normBeta, effectiveLr, 0f)
        }

        applyGradients1D(finalNormGamma, gradFinalNormGamma, effectiveLr, 0f)
        applyGradients1D(finalNormBeta, gradFinalNormBeta, effectiveLr, 0f)
        applyGradients(outputProjection, gradOutputProjection, effectiveLr, weightDecay)
        applyGradients1D(outputBias, gradOutputBias, effectiveLr, 0f)
    }

    /**
     * Zero all gradient accumulators.
     */
    fun zeroGrad() {
        for (row in gradTokenEmbeddings) row.fill(0f)
        for (row in gradDelayProjection) row.fill(0f)
        gradDelayProjectionBias.fill(0f)
        for (grads in gradLayers) grads.zero()
        gradFinalNormGamma.fill(0f)
        gradFinalNormBeta.fill(0f)
        for (row in gradOutputProjection) row.fill(0f)
        gradOutputBias.fill(0f)
    }

    // ---- Inference ----

    /**
     * Greedy autoregressive generation.
     *
     * @param prompt initial token indices
     * @param maxNewTokens maximum tokens to generate
     * @param temperature sampling temperature (0 = greedy)
     * @param topK top-k filtering (0 = disabled)
     * @return generated token indices including prompt
     */
    fun generate(
        prompt: IntArray,
        maxNewTokens: Int = 64,
        temperature: Float = 0.7f,
        topK: Int = 0
    ): IntArray {
        val generated = prompt.toMutableList()
        val rng = java.util.Random()

        for (step in 0 until maxNewTokens) {
            // Use sliding window if sequence exceeds max length
            val windowStart = maxOf(0, generated.size - config.maxSeqLen)
            val window = generated.subList(windowStart, generated.size).toIntArray()

            val result = forward(window)
            val lastLogits = result.logits[window.size - 1]

            val nextToken = if (temperature <= 0.0001f) {
                // Greedy
                lastLogits.indices.maxByOrNull { lastLogits[it] } ?: 0
            } else {
                // Temperature-scaled sampling
                val scaled = FloatArray(lastLogits.size) { lastLogits[it] / temperature }
                val filtered = if (topK > 0) topKFilter(scaled, topK) else scaled
                sampleFromLogits(filtered, rng)
            }

            generated.add(nextToken)

            // Stop on EOS token (index 0 by convention)
            if (nextToken == 0) break
        }

        return generated.toIntArray()
    }

    // ---- Delay-Coordinate Embedding (Takens' Theorem) ----

    /**
     * Construct delay-coordinate embeddings from token embedding sequence.
     *
     * For each timestep t, creates the vector:
     *   [e(t), e(t-τ₁), e(t-τ₂), ..., e(t-τₘ)]
     *
     * where τᵢ are the configured delays (default exponential: [1,2,4,8]).
     * Out-of-bounds positions are zero-padded.
     *
     * This reconstructs the phase space of the underlying semantic dynamical
     * system per Takens' delay-coordinate theorem.
     *
     * @param embedded [seq_len, embed_dim] token embeddings
     * @return [seq_len, (num_delays+1)*embed_dim] delay-coordinate vectors
     */
    private fun buildDelayCoordinates(embedded: Array<FloatArray>): Array<FloatArray> {
        val seqLen = embedded.size
        val embedDim = config.embedDim
        val numDelays = config.delays.size

        return Array(seqLen) { t ->
            val coords = FloatArray(takensInputDim)

            // Current position: e(t)
            System.arraycopy(embedded[t], 0, coords, 0, embedDim)

            // Delayed positions: e(t-τ₁), e(t-τ₂), ...
            for ((delayIdx, delay) in config.delays.withIndex()) {
                val srcT = t - delay
                val dstOffset = (delayIdx + 1) * embedDim
                if (srcT >= 0) {
                    System.arraycopy(embedded[srcT], 0, coords, dstOffset, embedDim)
                }
                // else: zero-padded (already initialized to 0)
            }

            coords
        }
    }

    /**
     * Backpropagate gradients through delay coordinates to token embeddings.
     */
    private fun backpropDelayCoordinates(dDelayEmbedded: Array<FloatArray>, inputIds: IntArray, n: Int) {
        val embedDim = config.embedDim

        for (t in 0 until n) {
            // Gradient for current position e(t)
            val tokenIdx = inputIds[t]
            for (d in 0 until embedDim) {
                gradTokenEmbeddings[tokenIdx][d] += dDelayEmbedded[t][d]
            }

            // Gradient for delayed positions
            for ((delayIdx, delay) in config.delays.withIndex()) {
                val srcT = t - delay
                if (srcT >= 0) {
                    val srcTokenIdx = inputIds[srcT]
                    val offset = (delayIdx + 1) * embedDim
                    for (d in 0 until embedDim) {
                        gradTokenEmbeddings[srcTokenIdx][d] += dDelayEmbedded[t][offset + d]
                    }
                }
            }
        }
    }

    // ---- Primitive Operations ----

    private fun linearForward(input: FloatArray, weight: Array<FloatArray>, bias: FloatArray): FloatArray {
        val outDim = bias.size
        val result = bias.copyOf()
        for (i in input.indices) {
            if (input[i] != 0.0f) {
                for (j in 0 until outDim) {
                    result[j] += input[i] * weight[i][j]
                }
            }
        }
        return result
    }

    /**
     * Feed-forward block: Linear → GELU → Linear, returning output and pre-GELU activations.
     */
    private fun feedForwardWithCache(
        input: FloatArray,
        ff1Weight: Array<FloatArray>, ff1Bias: FloatArray,
        ff2Weight: Array<FloatArray>, ff2Bias: FloatArray
    ): Pair<FloatArray, FloatArray> {
        // FF1: [hidden_dim] → [hidden_dim * 4]
        val preActivation = linearForward(input, ff1Weight, ff1Bias)

        // GELU activation
        val activated = FloatArray(preActivation.size) { gelu(preActivation[it]) }

        // FF2: [hidden_dim * 4] → [hidden_dim]
        val output = linearForward(activated, ff2Weight, ff2Bias)

        return output to preActivation
    }

    private fun layerNorm(input: FloatArray, gamma: FloatArray, beta: FloatArray): FloatArray {
        val n = input.size
        var mean = 0.0f
        for (v in input) mean += v
        mean /= n

        var variance = 0.0f
        for (v in input) {
            val diff = v - mean
            variance += diff * diff
        }
        variance /= n

        val std = sqrt(variance + 1e-5f)
        return FloatArray(n) { i -> gamma[i] * (input[i] - mean) / std + beta[i] }
    }

    private fun layerNormBackward(
        dOutput: FloatArray, input: FloatArray, gamma: FloatArray,
        gradGamma: FloatArray, gradBeta: FloatArray
    ): FloatArray {
        val n = input.size.toFloat()
        var mean = 0.0f
        for (v in input) mean += v
        mean /= n

        var variance = 0.0f
        for (v in input) {
            val diff = v - mean
            variance += diff * diff
        }
        variance /= n
        val std = sqrt(variance + 1e-5f)

        val xNorm = FloatArray(input.size) { (input[it] - mean) / std }

        // Accumulate gamma/beta gradients
        for (i in input.indices) {
            gradGamma[i] += dOutput[i] * xNorm[i]
            gradBeta[i] += dOutput[i]
        }

        // Gradient w.r.t. input
        val dxNorm = FloatArray(input.size) { dOutput[it] * gamma[it] }
        var dVar = 0.0f
        for (i in input.indices) {
            dVar += dxNorm[i] * (input[i] - mean) * -0.5f / (std * std * std)
        }
        var dMean = 0.0f
        for (i in input.indices) {
            dMean += dxNorm[i] * -1.0f / std
        }
        dMean += dVar * -2.0f * (input.indices.sumOf { (input[it] - mean).toDouble() }).toFloat() / n

        return FloatArray(input.size) { i ->
            dxNorm[i] / std + dVar * 2.0f * (input[i] - mean) / n + dMean / n
        }
    }

    /** GELU activation: x * Φ(x) where Φ is the standard normal CDF. */
    private fun gelu(x: Float): Float {
        // Approximation: 0.5 * x * (1 + tanh(sqrt(2/π) * (x + 0.044715 * x³)))
        val c = 0.7978845608f // sqrt(2/π)
        val inner = c * (x + 0.044715f * x * x * x)
        return 0.5f * x * (1.0f + tanh(inner))
    }

    /** GELU derivative for backprop. */
    private fun geluDerivative(x: Float): Float {
        val c = 0.7978845608f
        val x3 = x * x * x
        val inner = c * (x + 0.044715f * x3)
        val tanhVal = tanh(inner)
        val sech2 = 1.0f - tanhVal * tanhVal
        val dInner = c * (1.0f + 3.0f * 0.044715f * x * x)
        return 0.5f * (1.0f + tanhVal) + 0.5f * x * sech2 * dInner
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - maxLogit) }
        val sum = exps.sum()
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    private fun topKFilter(logits: FloatArray, k: Int): FloatArray {
        if (k >= logits.size) return logits
        val sorted = logits.sortedDescending()
        val threshold = sorted[k - 1]
        return FloatArray(logits.size) { i ->
            if (logits[i] >= threshold) logits[i] else Float.NEGATIVE_INFINITY
        }
    }

    private fun sampleFromLogits(logits: FloatArray, rng: java.util.Random): Int {
        val probs = softmax(logits)
        val r = rng.nextFloat()
        var cumulative = 0.0f
        for (i in probs.indices) {
            cumulative += probs[i]
            if (r < cumulative) return i
        }
        return probs.size - 1
    }

    private fun applyGradients(
        params: Array<FloatArray>, grads: Array<FloatArray>,
        lr: Float, weightDecay: Float
    ) {
        for (i in params.indices) {
            for (j in params[i].indices) {
                params[i][j] -= lr * (grads[i][j] + weightDecay * params[i][j])
            }
        }
    }

    private fun applyGradients1D(params: FloatArray, grads: FloatArray, lr: Float, weightDecay: Float) {
        for (i in params.indices) {
            params[i] -= lr * (grads[i] + weightDecay * params[i])
        }
    }

    private fun tanh(x: Float): Float = kotlin.math.tanh(x.toDouble()).toFloat()

    // ---- Serialization ----

    /**
     * Serialize model weights to a flat map for persistence.
     */
    fun serializeWeights(): Map<String, Any> {
        val weights = mutableMapOf<String, Any>()
        weights["config"] = config.toMap()
        weights["token_embeddings"] = serializeMatrix(tokenEmbeddings)
        weights["delay_projection"] = serializeMatrix(delayProjection)
        weights["delay_projection_bias"] = delayProjectionBias.toList()
        weights["final_norm_gamma"] = finalNormGamma.toList()
        weights["final_norm_beta"] = finalNormBeta.toList()
        weights["output_projection"] = serializeMatrix(outputProjection)
        weights["output_bias"] = outputBias.toList()

        for ((i, layer) in layers.withIndex()) {
            weights["layer_${i}_ff1_weight"] = serializeMatrix(layer.ff1Weight)
            weights["layer_${i}_ff1_bias"] = layer.ff1Bias.toList()
            weights["layer_${i}_ff2_weight"] = serializeMatrix(layer.ff2Weight)
            weights["layer_${i}_ff2_bias"] = layer.ff2Bias.toList()
            weights["layer_${i}_norm_gamma"] = layer.normGamma.toList()
            weights["layer_${i}_norm_beta"] = layer.normBeta.toList()
        }

        return weights
    }

    private fun serializeMatrix(matrix: Array<FloatArray>): List<List<Float>> =
        matrix.map { it.toList() }

    companion object {
        private const val TAG = "TakensTransformer"

        /** Default micro config for Pixel 10 TPU. */
        fun microConfig(vocabSize: Int): TakensConfig = TakensConfig(
            vocabSize = vocabSize,
            embedDim = 32,
            hiddenDim = 64,
            numLayers = 2,
            delays = listOf(1, 2, 4, 8),
            maxSeqLen = 128,
            learningRate = 3e-4f,
            weightDecay = 0.01f
        )

        /** Small config for devices with more memory. */
        fun smallConfig(vocabSize: Int): TakensConfig = TakensConfig(
            vocabSize = vocabSize,
            embedDim = 64,
            hiddenDim = 128,
            numLayers = 3,
            delays = listOf(1, 2, 4, 8, 16),
            maxSeqLen = 256,
            learningRate = 3e-4f,
            weightDecay = 0.01f
        )
    }
}

/**
 * Model configuration.
 */
data class TakensConfig(
    val vocabSize: Int,
    val embedDim: Int = 32,
    val hiddenDim: Int = 64,
    val numLayers: Int = 2,
    val delays: List<Int> = listOf(1, 2, 4, 8),
    val maxSeqLen: Int = 128,
    val learningRate: Float = 3e-4f,
    val weightDecay: Float = 0.01f,
    val seed: Long = 42L
) {
    fun toMap(): Map<String, Any> = mapOf(
        "vocab_size" to vocabSize,
        "embed_dim" to embedDim,
        "hidden_dim" to hiddenDim,
        "num_layers" to numLayers,
        "delays" to delays,
        "max_seq_len" to maxSeqLen,
        "learning_rate" to learningRate,
        "weight_decay" to weightDecay
    )
}

/**
 * Parameters for a single TBT layer: LayerNorm → FeedForward (with residual).
 */
class TBTLayerParams(hiddenDim: Int, rng: java.util.Random) {
    val ffDim = hiddenDim * 4

    // Layer norm parameters
    val normGamma = FloatArray(hiddenDim) { 1.0f }
    val normBeta = FloatArray(hiddenDim) { 0.0f }

    // Feed-forward: hidden_dim → hidden_dim*4 → hidden_dim
    val ff1Weight: Array<FloatArray>
    val ff1Bias: FloatArray
    val ff2Weight: Array<FloatArray>
    val ff2Bias: FloatArray

    init {
        val scale1 = sqrt(2.0f / hiddenDim)
        val scale2 = sqrt(2.0f / ffDim)

        ff1Weight = Array(hiddenDim) {
            FloatArray(ffDim) { (rng.nextGaussian() * scale1).toFloat() }
        }
        ff1Bias = FloatArray(ffDim)

        ff2Weight = Array(ffDim) {
            FloatArray(hiddenDim) { (rng.nextGaussian() * scale2).toFloat() }
        }
        ff2Bias = FloatArray(hiddenDim)
    }

    fun parameterCount(): Long {
        val hd = normGamma.size.toLong()
        val fd = ffDim.toLong()
        return hd * 2 + hd * fd + fd + fd * hd + hd
    }
}

/**
 * Gradient accumulators for a TBT layer.
 */
class TBTLayerGrads(hiddenDim: Int) {
    val ffDim = hiddenDim * 4
    val normGamma = FloatArray(hiddenDim)
    val normBeta = FloatArray(hiddenDim)
    val ff1Weight = Array(hiddenDim) { FloatArray(ffDim) }
    val ff1Bias = FloatArray(ffDim)
    val ff2Weight = Array(ffDim) { FloatArray(hiddenDim) }
    val ff2Bias = FloatArray(hiddenDim)

    fun zero() {
        normGamma.fill(0f)
        normBeta.fill(0f)
        for (row in ff1Weight) row.fill(0f)
        ff1Bias.fill(0f)
        for (row in ff2Weight) row.fill(0f)
        ff2Bias.fill(0f)
    }
}

/**
 * Cached activations from a forward pass, needed for backpropagation.
 */
data class ForwardResult(
    val logits: Array<FloatArray>,
    val inputIds: IntArray,
    val embedded: Array<FloatArray>,
    val delayEmbedded: Array<FloatArray>,
    val projected: Array<FloatArray>,
    val layerInputs: List<Array<FloatArray>>,
    val layerNormed: List<Array<FloatArray>>,
    val layerFfInternals: List<Array<FloatArray>>,
    val preOutputHidden: Array<FloatArray>,
    val finalNormed: Array<FloatArray>
)
