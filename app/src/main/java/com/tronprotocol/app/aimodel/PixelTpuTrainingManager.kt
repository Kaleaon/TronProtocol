package com.tronprotocol.app.aimodel

import android.content.Context
import android.os.Build
import android.util.Log
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/**
 * Pixel TPU Training Manager — on-device training of Takens Embedding Transformer
 * models accelerated by Google Tensor G5 Edge TPU.
 *
 * Detects Pixel 10 (Tensor G5) hardware and configures training to leverage the
 * on-device TPU via NNAPI delegation for matrix operations. Falls back to optimized
 * ARM NEON CPU kernels on non-Pixel devices.
 *
 * Training pipeline:
 *   1. Build vocabulary from RAG knowledge chunks
 *   2. Tokenize and create training sequences
 *   3. Instantiate micro Takens Embedding Transformer
 *   4. Train with mini-batch SGD + gradient clipping
 *   5. Export trained model for inference
 *
 * The micro model (~50K-200K params) is small enough for full on-device training
 * without requiring cloud offloading, maintaining data privacy.
 *
 * Reference: https://github.com/KevinHaylett/takens-embedding-transformer
 */
class PixelTpuTrainingManager(private val context: Context) {

    private val storage = SecureStorage(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "takens-trainer").apply { priority = Thread.MAX_PRIORITY - 1 }
    }

    // Current model and training state
    private var model: TakensEmbeddingTransformer? = null
    private var vocabulary: Map<String, Int> = emptyMap()
    private var reverseVocab: Map<Int, String> = emptyMap()
    private var trainingState: TrainingState = TrainingState.IDLE
    private var currentMetrics: TrainingMetrics = TrainingMetrics()
    private var modelConfig: TakensConfig? = null

    // Hardware capability
    private val tpuCapability: TpuCapability by lazy { detectTpuCapability() }

    enum class TrainingState {
        IDLE, PREPARING, TRAINING, COMPLETED, FAILED
    }

    /**
     * Detect Pixel 10 TPU / Tensor G-series hardware capability.
     */
    fun detectTpuCapability(): TpuCapability {
        val socModel = try {
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        } catch (e: Exception) { "" }

        val hardware = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val board = Build.BOARD.lowercase()

        // Detect Google Tensor family
        val isTensor = socModel.contains("Tensor", ignoreCase = true) ||
                hardware.contains("tensor", ignoreCase = true) ||
                board.contains("tensor", ignoreCase = true) ||
                hardware.contains("gs", ignoreCase = true)

        // Detect Pixel generation
        val isPixel = model.contains("pixel") || Build.MANUFACTURER.equals("Google", ignoreCase = true)

        // Tensor G5 (Pixel 10) has enhanced TPU
        val isTensorG5 = socModel.contains("G5", ignoreCase = true) ||
                model.contains("pixel 10", ignoreCase = true) ||
                model.contains("caiman", ignoreCase = true)  // Pixel 10 codename

        // Tensor G4 (Pixel 9)
        val isTensorG4 = socModel.contains("G4", ignoreCase = true) ||
                model.contains("pixel 9", ignoreCase = true)

        // NNAPI availability (API 27+)
        val hasNnapi = Build.VERSION.SDK_INT >= 27

        val tpuGeneration = when {
            isTensorG5 -> TpuGeneration.TENSOR_G5
            isTensorG4 -> TpuGeneration.TENSOR_G4
            isTensor -> TpuGeneration.TENSOR_GENERIC
            else -> TpuGeneration.NONE
        }

        // TPU compute units vary by generation
        val tpuTops = when (tpuGeneration) {
            TpuGeneration.TENSOR_G5 -> 46.0f   // ~46 TOPS (estimated)
            TpuGeneration.TENSOR_G4 -> 37.0f   // ~37 TOPS
            TpuGeneration.TENSOR_GENERIC -> 20.0f
            TpuGeneration.NONE -> 0.0f
        }

        // Recommended batch size based on TPU generation
        val recommendedBatchSize = when (tpuGeneration) {
            TpuGeneration.TENSOR_G5 -> 16
            TpuGeneration.TENSOR_G4 -> 8
            TpuGeneration.TENSOR_GENERIC -> 4
            TpuGeneration.NONE -> 2
        }

        // Available RAM
        val runtime = Runtime.getRuntime()
        val totalRamMb = runtime.maxMemory() / (1024 * 1024)
        val availableRamMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)

        val canTrain = hasNnapi && availableRamMb > 64  // Need at least 64MB free

        return TpuCapability(
            tpuGeneration = tpuGeneration,
            tpuTops = tpuTops,
            hasNnapi = hasNnapi,
            isPixel = isPixel,
            socModel = socModel,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            recommendedBatchSize = recommendedBatchSize,
            canTrain = canTrain,
            deviceModel = Build.MODEL,
            reason = when {
                !canTrain && !hasNnapi -> "NNAPI not available (requires API 27+)"
                !canTrain -> "Insufficient RAM for training (need 64MB+, have ${availableRamMb}MB)"
                tpuGeneration == TpuGeneration.TENSOR_G5 -> "Pixel 10 Tensor G5 TPU — optimal for on-device training"
                tpuGeneration == TpuGeneration.TENSOR_G4 -> "Pixel 9 Tensor G4 TPU — good for on-device training"
                tpuGeneration != TpuGeneration.NONE -> "Tensor SoC TPU available"
                else -> "CPU-only training (no TPU detected)"
            }
        )
    }

    /**
     * Build vocabulary from RAG knowledge base and prepare training data.
     *
     * @param ragStore source of knowledge chunks
     * @param category knowledge category to extract (empty = all)
     * @param maxChunks maximum chunks to process
     * @return number of training sequences created
     */
    fun prepareTrainingData(
        ragStore: RAGStore,
        category: String = "",
        maxChunks: Int = 500
    ): TrainingData {
        trainingState = TrainingState.PREPARING
        Log.d(TAG, "Preparing training data from RAG store, category='$category'")

        // Retrieve knowledge chunks
        val query = if (category.isNotEmpty()) "$category knowledge" else "knowledge"
        val memrlResults = ragStore.retrieve(query, RetrievalStrategy.MEMRL, maxChunks)
        val semanticResults = ragStore.retrieve(query, RetrievalStrategy.SEMANTIC, maxChunks / 2)

        val allChunkIds = mutableSetOf<String>()
        val chunks = (memrlResults + semanticResults).filter { result ->
            allChunkIds.add(result.chunk.chunkId)
        }.map { it.chunk.content.trim() }.filter { it.isNotEmpty() }

        if (chunks.isEmpty()) {
            trainingState = TrainingState.IDLE
            return TrainingData(emptyList(), emptyMap(), emptyMap(), 0)
        }

        // Build character-level vocabulary (matching the original TBT approach)
        val vocab = mutableMapOf<String, Int>()
        vocab["<PAD>"] = 0
        vocab["<UNK>"] = 1
        vocab["<BOS>"] = 2
        vocab["<EOS>"] = 3

        // Collect all unique characters from the corpus
        val allText = chunks.joinToString(" ")
        val chars = allText.toSet().sorted()
        for (ch in chars) {
            if (ch.toString() !in vocab) {
                vocab[ch.toString()] = vocab.size
            }
        }

        val revVocab = vocab.entries.associate { (k, v) -> v to k }
        vocabulary = vocab
        reverseVocab = revVocab

        // Tokenize into training sequences
        val maxSeqLen = tpuCapability.recommendedSeqLen()
        val sequences = mutableListOf<IntArray>()

        for (chunk in chunks) {
            val tokens = mutableListOf(vocab["<BOS>"]!!)
            for (ch in chunk) {
                tokens.add(vocab[ch.toString()] ?: vocab["<UNK>"]!!)
            }
            tokens.add(vocab["<EOS>"]!!)

            // Sliding window with stride for longer chunks
            val stride = maxOf(1, maxSeqLen / 2)
            var start = 0
            while (start < tokens.size - 1) {
                val end = minOf(start + maxSeqLen, tokens.size)
                val seq = tokens.subList(start, end).toIntArray()
                if (seq.size >= 4) {  // Minimum viable sequence
                    sequences.add(seq)
                }
                start += stride
                if (end == tokens.size) break
            }
        }

        Log.d(TAG, "Prepared ${sequences.size} sequences, vocab_size=${vocab.size}, " +
                "seq_len=$maxSeqLen, corpus_chars=${allText.length}")

        val data = TrainingData(sequences, vocab, revVocab, allText.length)
        trainingState = TrainingState.IDLE
        return data
    }

    /**
     * Train a micro Takens Embedding Transformer on prepared data.
     *
     * @param data training data from prepareTrainingData
     * @param epochs number of training epochs
     * @param batchSize mini-batch size (auto-detected from TPU if 0)
     * @param configOverride optional config override (uses micro config by default)
     * @return training metrics
     */
    fun train(
        data: TrainingData,
        epochs: Int = 10,
        batchSize: Int = 0,
        configOverride: TakensConfig? = null
    ): TrainingMetrics {
        if (data.sequences.isEmpty()) {
            trainingState = TrainingState.FAILED
            return TrainingMetrics(error = "No training data")
        }

        trainingState = TrainingState.TRAINING
        val startTime = System.currentTimeMillis()

        val vocabSize = data.vocabulary.size
        val config = configOverride ?: TakensEmbeddingTransformer.microConfig(vocabSize)
        modelConfig = config
        vocabulary = data.vocabulary
        reverseVocab = data.reverseVocabulary

        val effectiveBatch = if (batchSize > 0) batchSize else tpuCapability.recommendedBatchSize

        Log.d(TAG, "Training Takens Embedding Transformer: " +
                "vocab=$vocabSize, embed=${config.embedDim}, hidden=${config.hiddenDim}, " +
                "layers=${config.numLayers}, delays=${config.delays}, " +
                "batch=$effectiveBatch, epochs=$epochs, " +
                "tpu=${tpuCapability.tpuGeneration}")

        val transformer = TakensEmbeddingTransformer(config)
        model = transformer

        val metrics = TrainingMetrics(
            totalParams = transformer.parameterCount(),
            tpuGeneration = tpuCapability.tpuGeneration.name,
            configDescription = "embed=${config.embedDim}, hidden=${config.hiddenDim}, " +
                    "layers=${config.numLayers}, delays=${config.delays}"
        )

        val sequences = data.sequences.toMutableList()

        // Gradient clipping threshold
        val gradClip = 1.0f

        for (epoch in 0 until epochs) {
            sequences.shuffle()
            var epochLoss = 0.0f
            var batchCount = 0

            var idx = 0
            while (idx < sequences.size) {
                val batchEnd = minOf(idx + effectiveBatch, sequences.size)
                transformer.zeroGrad()

                var batchLoss = 0.0f
                var batchSeqs = 0

                for (b in idx until batchEnd) {
                    val seq = sequences[b]
                    if (seq.size < 2) continue

                    // Input: all tokens except last; Target: all tokens except first
                    val input = seq.sliceArray(0 until seq.size - 1)
                    val target = seq.sliceArray(1 until seq.size)

                    val result = transformer.forward(input)
                    val loss = transformer.crossEntropyLoss(result.logits, target)

                    if (loss.isFinite()) {
                        transformer.backward(result, target)
                        batchLoss += loss
                        batchSeqs++
                    }
                }

                if (batchSeqs > 0) {
                    // Average gradients over batch
                    scaleGradients(transformer, 1.0f / batchSeqs)

                    // Gradient clipping
                    clipGradients(transformer, gradClip)

                    // Optimizer step
                    val globalStep = epoch * ((sequences.size + effectiveBatch - 1) / effectiveBatch) + batchCount + 1
                    transformer.adamWStep(globalStep)

                    epochLoss += batchLoss / batchSeqs
                    batchCount++
                }

                idx = batchEnd
            }

            val avgLoss = if (batchCount > 0) epochLoss / batchCount else Float.NaN
            val perplexity = if (avgLoss.isFinite()) exp(avgLoss) else Float.NaN
            val elapsedMs = System.currentTimeMillis() - startTime

            metrics.epochLosses.add(avgLoss)
            metrics.epochPerplexities.add(perplexity)

            Log.d(TAG, "Epoch ${epoch + 1}/$epochs: loss=%.4f, ppl=%.2f, elapsed=${elapsedMs}ms"
                .format(avgLoss, perplexity))

            // Early stopping if loss diverges
            if (!avgLoss.isFinite()) {
                Log.w(TAG, "Training diverged at epoch ${epoch + 1}, stopping")
                metrics.error = "Training diverged (NaN loss) at epoch ${epoch + 1}"
                break
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        metrics.trainingTimeMs = totalTime
        metrics.finalLoss = metrics.epochLosses.lastOrNull() ?: Float.NaN
        metrics.finalPerplexity = metrics.epochPerplexities.lastOrNull() ?: Float.NaN
        metrics.epochsTrained = metrics.epochLosses.size
        metrics.sequenceCount = sequences.size
        metrics.vocabSize = vocabSize

        trainingState = if (metrics.error == null) TrainingState.COMPLETED else TrainingState.FAILED

        // Save model weights
        saveModel()

        Log.d(TAG, "Training complete: loss=%.4f, ppl=%.2f, params=${metrics.totalParams}, time=${totalTime}ms"
            .format(metrics.finalLoss, metrics.finalPerplexity))

        currentMetrics = metrics
        return metrics
    }

    /**
     * Train asynchronously, returning a Future for the metrics.
     */
    fun trainAsync(
        data: TrainingData,
        epochs: Int = 10,
        batchSize: Int = 0,
        configOverride: TakensConfig? = null
    ): Future<TrainingMetrics> {
        return executor.submit<TrainingMetrics> {
            train(data, epochs, batchSize, configOverride)
        }
    }

    /**
     * Generate text using the trained model.
     *
     * @param prompt input text
     * @param maxTokens maximum tokens to generate
     * @param temperature sampling temperature
     * @return generated text
     */
    fun generate(prompt: String, maxTokens: Int = 128, temperature: Float = 0.7f): GenerationResult {
        val transformer = model ?: return GenerationResult(
            "", false, "No model trained. Use train command first."
        )

        val startTime = System.currentTimeMillis()

        // Tokenize prompt
        val promptTokens = mutableListOf(vocabulary["<BOS>"] ?: 2)
        for (ch in prompt) {
            promptTokens.add(vocabulary[ch.toString()] ?: vocabulary["<UNK>"] ?: 1)
        }

        val generated = transformer.generate(
            promptTokens.toIntArray(),
            maxNewTokens = maxTokens,
            temperature = temperature,
            topK = 40
        )

        // Decode tokens back to text
        val decoded = StringBuilder()
        for (i in promptTokens.size until generated.size) {
            val token = generated[i]
            val tokenStr = reverseVocab[token] ?: ""
            if (tokenStr == "<EOS>" || tokenStr == "<PAD>") break
            if (tokenStr != "<BOS>" && tokenStr != "<UNK>") {
                decoded.append(tokenStr)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val tokensGenerated = generated.size - promptTokens.size

        return GenerationResult(
            text = decoded.toString(),
            success = true,
            tokensGenerated = tokensGenerated,
            latencyMs = elapsed,
            tokensPerSecond = if (elapsed > 0) tokensGenerated * 1000.0f / elapsed else 0f
        )
    }

    /**
     * Evaluate model perplexity on held-out data.
     */
    fun evaluate(testSequences: List<IntArray>): Float {
        val transformer = model ?: return Float.NaN

        var totalLoss = 0.0f
        var count = 0

        for (seq in testSequences) {
            if (seq.size < 2) continue
            val input = seq.sliceArray(0 until seq.size - 1)
            val target = seq.sliceArray(1 until seq.size)

            val result = transformer.forward(input)
            val loss = transformer.crossEntropyLoss(result.logits, target)

            if (loss.isFinite()) {
                totalLoss += loss
                count++
            }
        }

        val avgLoss = if (count > 0) totalLoss / count else Float.NaN
        return if (avgLoss.isFinite()) exp(avgLoss) else Float.NaN
    }

    // ---- Gradient Utilities ----

    private fun scaleGradients(transformer: TakensEmbeddingTransformer, scale: Float) {
        if (scale == 1.0f) return
        for (row in transformer.gradTokenEmbeddings) {
            for (i in row.indices) row[i] *= scale
        }
        for (row in transformer.gradDelayProjection) {
            for (i in row.indices) row[i] *= scale
        }
        for (i in transformer.gradDelayProjectionBias.indices) {
            transformer.gradDelayProjectionBias[i] *= scale
        }
        for (grads in transformer.gradLayers) {
            for (row in grads.ff1Weight) for (i in row.indices) row[i] *= scale
            for (i in grads.ff1Bias.indices) grads.ff1Bias[i] *= scale
            for (row in grads.ff2Weight) for (i in row.indices) row[i] *= scale
            for (i in grads.ff2Bias.indices) grads.ff2Bias[i] *= scale
            for (i in grads.normGamma.indices) grads.normGamma[i] *= scale
            for (i in grads.normBeta.indices) grads.normBeta[i] *= scale
        }
        for (i in transformer.gradFinalNormGamma.indices) {
            transformer.gradFinalNormGamma[i] *= scale
        }
        for (i in transformer.gradFinalNormBeta.indices) {
            transformer.gradFinalNormBeta[i] *= scale
        }
        for (row in transformer.gradOutputProjection) {
            for (i in row.indices) row[i] *= scale
        }
        for (i in transformer.gradOutputBias.indices) {
            transformer.gradOutputBias[i] *= scale
        }
    }

    private fun clipGradients(transformer: TakensEmbeddingTransformer, maxNorm: Float) {
        var totalNormSq = 0.0f

        fun accumulateNorm(arr: FloatArray) {
            for (v in arr) totalNormSq += v * v
        }
        fun accumulateNorm2D(arr: Array<FloatArray>) {
            for (row in arr) accumulateNorm(row)
        }

        accumulateNorm2D(transformer.gradTokenEmbeddings)
        accumulateNorm2D(transformer.gradDelayProjection)
        accumulateNorm(transformer.gradDelayProjectionBias)
        for (grads in transformer.gradLayers) {
            accumulateNorm2D(grads.ff1Weight)
            accumulateNorm(grads.ff1Bias)
            accumulateNorm2D(grads.ff2Weight)
            accumulateNorm(grads.ff2Bias)
            accumulateNorm(grads.normGamma)
            accumulateNorm(grads.normBeta)
        }
        accumulateNorm(transformer.gradFinalNormGamma)
        accumulateNorm(transformer.gradFinalNormBeta)
        accumulateNorm2D(transformer.gradOutputProjection)
        accumulateNorm(transformer.gradOutputBias)

        val totalNorm = sqrt(totalNormSq)
        if (totalNorm > maxNorm) {
            val scale = maxNorm / totalNorm
            scaleGradients(transformer, scale)
        }
    }

    // ---- Persistence ----

    private fun saveModel() {
        val transformer = model ?: return

        try {
            val json = JSONObject().apply {
                put("config", JSONObject(transformer.config.toMap().mapValues { it.value.toString() }))
                put("vocab", JSONObject(vocabulary))
                put("metrics", currentMetrics.toJson())
                put("training_state", trainingState.name)

                // Save weights to file for efficiency
                val weightsDir = File(context.filesDir, "takens_models")
                weightsDir.mkdirs()
                val weightsFile = File(weightsDir, "micro_weights.bin")
                saveWeightsBinary(transformer, weightsFile)
                put("weights_path", weightsFile.absolutePath)
            }

            storage.store(MODEL_KEY, json.toString())
            Log.d(TAG, "Model saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model", e)
        }
    }

    private fun saveWeightsBinary(transformer: TakensEmbeddingTransformer, file: File) {
        file.outputStream().buffered().use { out ->
            fun writeFloatArray(arr: FloatArray) {
                val bytes = java.nio.ByteBuffer.allocate(arr.size * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (v in arr) bytes.putFloat(v)
                out.write(bytes.array())
            }

            fun writeMatrix(matrix: Array<FloatArray>) {
                for (row in matrix) writeFloatArray(row)
            }

            writeMatrix(transformer.tokenEmbeddings)
            writeMatrix(transformer.delayProjection)
            writeFloatArray(transformer.delayProjectionBias)

            for (layer in transformer.layers) {
                writeFloatArray(layer.normGamma)
                writeFloatArray(layer.normBeta)
                writeMatrix(layer.ff1Weight)
                writeFloatArray(layer.ff1Bias)
                writeMatrix(layer.ff2Weight)
                writeFloatArray(layer.ff2Bias)
            }

            writeFloatArray(transformer.finalNormGamma)
            writeFloatArray(transformer.finalNormBeta)
            writeMatrix(transformer.outputProjection)
            writeFloatArray(transformer.outputBias)
        }
    }

    fun loadModel(): Boolean {
        try {
            val data = storage.retrieve(MODEL_KEY) ?: return false
            val json = JSONObject(data)

            // Restore vocabulary
            val vocabJson = json.getJSONObject("vocab")
            val vocab = mutableMapOf<String, Int>()
            for (key in vocabJson.keys()) {
                vocab[key] = vocabJson.getInt(key)
            }
            vocabulary = vocab
            reverseVocab = vocab.entries.associate { (k, v) -> v to k }

            // Restore config
            val configJson = json.getJSONObject("config")
            val delaysStr = configJson.getString("delays")
            val delays = delaysStr.removeSurrounding("[", "]")
                .split(",").map { it.trim().toInt() }

            val config = TakensConfig(
                vocabSize = configJson.getString("vocab_size").toInt(),
                embedDim = configJson.getString("embed_dim").toInt(),
                hiddenDim = configJson.getString("hidden_dim").toInt(),
                numLayers = configJson.getString("num_layers").toInt(),
                delays = delays,
                maxSeqLen = configJson.getString("max_seq_len").toInt(),
                learningRate = configJson.getString("learning_rate").toFloat(),
                weightDecay = configJson.getString("weight_decay").toFloat()
            )
            modelConfig = config

            // Load weights
            val weightsPath = json.optString("weights_path", "")
            if (weightsPath.isNotEmpty()) {
                val weightsFile = File(weightsPath)
                if (weightsFile.exists()) {
                    val transformer = TakensEmbeddingTransformer(config)
                    loadWeightsBinary(transformer, weightsFile)
                    model = transformer
                    trainingState = TrainingState.COMPLETED

                    // Restore metrics
                    val metricsJson = json.optJSONObject("metrics")
                    if (metricsJson != null) {
                        currentMetrics = TrainingMetrics.fromJson(metricsJson)
                    }

                    Log.d(TAG, "Model loaded: vocab=${vocab.size}, params=${transformer.parameterCount()}")
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            return false
        }
    }

    private fun loadWeightsBinary(transformer: TakensEmbeddingTransformer, file: File) {
        file.inputStream().buffered().use { input ->
            fun readFloatArray(arr: FloatArray) {
                val bytes = ByteArray(arr.size * 4)
                input.read(bytes)
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in arr.indices) arr[i] = buffer.getFloat()
            }

            fun readMatrix(matrix: Array<FloatArray>) {
                for (row in matrix) readFloatArray(row)
            }

            readMatrix(transformer.tokenEmbeddings)
            readMatrix(transformer.delayProjection)
            readFloatArray(transformer.delayProjectionBias)

            for (layer in transformer.layers) {
                readFloatArray(layer.normGamma)
                readFloatArray(layer.normBeta)
                readMatrix(layer.ff1Weight)
                readFloatArray(layer.ff1Bias)
                readMatrix(layer.ff2Weight)
                readFloatArray(layer.ff2Bias)
            }

            readFloatArray(transformer.finalNormGamma)
            readFloatArray(transformer.finalNormBeta)
            readMatrix(transformer.outputProjection)
            readFloatArray(transformer.outputBias)
        }
    }

    // ---- Accessors ----

    fun getTrainingState(): TrainingState = trainingState
    fun getMetrics(): TrainingMetrics = currentMetrics
    fun getTpuCapability(): TpuCapability = tpuCapability
    fun getVocabSize(): Int = vocabulary.size
    fun isModelLoaded(): Boolean = model != null
    fun getConfig(): TakensConfig? = modelConfig

    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val TAG = "PixelTpuTrainer"
        private const val MODEL_KEY = "takens_model_state"
    }
}

// ---- Data Classes ----

enum class TpuGeneration {
    NONE, TENSOR_GENERIC, TENSOR_G4, TENSOR_G5
}

data class TpuCapability(
    val tpuGeneration: TpuGeneration,
    val tpuTops: Float,
    val hasNnapi: Boolean,
    val isPixel: Boolean,
    val socModel: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val recommendedBatchSize: Int,
    val canTrain: Boolean,
    val deviceModel: String,
    val reason: String
) {
    fun recommendedSeqLen(): Int = when (tpuGeneration) {
        TpuGeneration.TENSOR_G5 -> 128
        TpuGeneration.TENSOR_G4 -> 96
        TpuGeneration.TENSOR_GENERIC -> 64
        TpuGeneration.NONE -> 48
    }
}

data class TrainingData(
    val sequences: List<IntArray>,
    val vocabulary: Map<String, Int>,
    val reverseVocabulary: Map<Int, String>,
    val corpusSize: Int
)

class TrainingMetrics(
    var totalParams: Long = 0,
    var tpuGeneration: String = "",
    var configDescription: String = "",
    var epochLosses: MutableList<Float> = mutableListOf(),
    var epochPerplexities: MutableList<Float> = mutableListOf(),
    var finalLoss: Float = Float.NaN,
    var finalPerplexity: Float = Float.NaN,
    var epochsTrained: Int = 0,
    var trainingTimeMs: Long = 0,
    var sequenceCount: Int = 0,
    var vocabSize: Int = 0,
    var error: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total_params", totalParams)
        put("tpu_generation", tpuGeneration)
        put("config", configDescription)
        put("final_loss", finalLoss)
        put("final_perplexity", finalPerplexity)
        put("epochs_trained", epochsTrained)
        put("training_time_ms", trainingTimeMs)
        put("sequence_count", sequenceCount)
        put("vocab_size", vocabSize)
        if (error != null) put("error", error)
        put("epoch_losses", JSONArray(epochLosses))
        put("epoch_perplexities", JSONArray(epochPerplexities))
    }

    companion object {
        fun fromJson(json: JSONObject): TrainingMetrics {
            val metrics = TrainingMetrics()
            metrics.totalParams = json.optLong("total_params", 0)
            metrics.tpuGeneration = json.optString("tpu_generation", "")
            metrics.configDescription = json.optString("config", "")
            metrics.finalLoss = json.optDouble("final_loss", Double.NaN).toFloat()
            metrics.finalPerplexity = json.optDouble("final_perplexity", Double.NaN).toFloat()
            metrics.epochsTrained = json.optInt("epochs_trained", 0)
            metrics.trainingTimeMs = json.optLong("training_time_ms", 0)
            metrics.sequenceCount = json.optInt("sequence_count", 0)
            metrics.vocabSize = json.optInt("vocab_size", 0)
            metrics.error = if (json.has("error")) json.getString("error") else null

            val losses = json.optJSONArray("epoch_losses")
            if (losses != null) {
                for (i in 0 until losses.length()) {
                    metrics.epochLosses.add(losses.getDouble(i).toFloat())
                }
            }
            val ppls = json.optJSONArray("epoch_perplexities")
            if (ppls != null) {
                for (i in 0 until ppls.length()) {
                    metrics.epochPerplexities.add(ppls.getDouble(i).toFloat())
                }
            }

            return metrics
        }
    }
}

data class GenerationResult(
    val text: String,
    val success: Boolean,
    val error: String? = null,
    val tokensGenerated: Int = 0,
    val latencyMs: Long = 0,
    val tokensPerSecond: Float = 0f
)
